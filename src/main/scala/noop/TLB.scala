package noop

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import bus.simplebus._
import bus.axi4._
import utils._

trait Sv39Const{
  val vpnLen = 27
  val ppnLen = 44
  val Level = 3
  val ppn2Len = 26
  val ppn1Len = 9
  val ppn0Len = 9
  val offLen  = 12
  val vpn2Len = 9
  val vpn1Len = 9
  val vpn0Len = 9
  val vaResLen= 25
  val paResLen= 8

  val paddrLen = 64
  val vaddrLen = 64
  val satpLen = 64
  val satpModeLen = 4
  val asidLen = 16
  val flagLen = 8
  val ptEntryLen = 64

  def vaBundle = new Bundle {
    val reserverd = UInt(vaResLen.W)
    val vpn2 = UInt(vpn2Len.W)
    val vpn1 = UInt(vpn1Len.W)
    val vpn0 = UInt(vpn0Len.W)
    val off  = UInt( offLen.W)
  }

  def vaBundle2 = new Bundle {
    val reserverd = UInt(vaResLen.W)
    val vpn  = UInt(vpnLen.W)
    val off  = UInt(offLen.W)
  }

  def vpnBundle = new Bundle {
    val vpn2 = UInt(vpn2Len.W)
    val vpn1 = UInt(vpn1Len.W)
    val vpn0 = UInt(vpn0Len.W)
  }

  def paBundle = new Bundle {
    val reserved = UInt(paResLen.W)
    val ppn2 = UInt(ppn2Len.W)
    val ppn1 = UInt(ppn1Len.W)
    val ppn0 = UInt(ppn0Len.W)
    val off  = UInt( offLen.W)
  }

  def paBundle2 = new Bundle {
    val reserved = UInt(paResLen.W)
    val ppn  = UInt(ppnLen.W)
    val off  = UInt(offLen.W)
  }
  
  def paddrApply(ppn: UInt, off: UInt):UInt = {
    Cat(Cat(0.U(paResLen.W), Cat(ppn, off)), 0.U(3.W))
  }
  
  def pteBundle = new Bundle {
    val reserved  = UInt(10.W)
    val ppn  = UInt(ppnLen.W)
    val rsw  = UInt(2.W)
    val flag = new Bundle {
      val d    = UInt(1.W)
      val a    = UInt(1.W)
      val g    = UInt(1.W)
      val u    = UInt(1.W)
      val x    = UInt(1.W)
      val w    = UInt(1.W)
      val r    = UInt(1.W)
      val v    = UInt(1.W)
    }
  }

  def satpBundle = new Bundle {
    val mode = UInt(satpModeLen.W)
    val asid = UInt(asidLen.W)
    val ppn  = UInt(ppnLen.W)
  }

  def flagBundle = new Bundle {
    val d    = Bool()//UInt(1.W)
    val a    = Bool()//UInt(1.W)
    val g    = Bool()//UInt(1.W)
    val u    = Bool()//UInt(1.W)
    val x    = Bool()//UInt(1.W)
    val w    = Bool()//UInt(1.W)
    val r    = Bool()//UInt(1.W)
    val v    = Bool()//UInt(1.W)
  }

  def vmMux(userBits: Int = 0, en: Bool, enYes: SimpleBusReqBundle, enNo: SimpleBusReqBundle) = {
    val res = Wire(new SimpleBusReqBundle(userBits))
    res.addr := Mux(en, enYes.addr, enNo.addr)
    res.size := Mux(en, enYes.size, enNo.size)
    res.cmd  := Mux(en,  enYes.cmd,  enNo.cmd)
    res.wmask:= Mux(en,enYes.wmask,enNo.wmask)
    res.wdata:= Mux(en,enYes.wdata,enNo.wdata)
    if(userBits > 0) { res.user.map(_ := Mux(en, enYes.user.getOrElse(0.U),enNo.user.getOrElse(0.U))) }
    res
  }

  def maskPaddr(ppn:UInt, vaddr:UInt, mask:UInt) = {
    MaskData(vaddr, Cat(0.U(paResLen.W), ppn, 0.U(offLen.W)), Cat("h1ffffff".U(25.W), "h1ff".U(vpn2Len.W), mask, 0.U(offLen.W)))
  }

  def MaskEQ(mask: UInt, pattern: UInt, vpn: UInt) = {
    (Cat("h1ff".U(vpn2Len.W), mask) & pattern) === (Cat("h1ff".U(vpn2Len.W), mask) & vpn)
  }

}

case class TLBConfig (
  name: String = "tlb",
  userBits: Int = 0,

  totalSize: Int = 128, 
  entryNum: Int = 4
)

sealed trait HasTlbConst extends Sv39Const{
  implicit val tlbConfig: TLBConfig

  val AddrBits: Int
  val XLEN: Int

  val tlbname = tlbConfig.name
  val userBits = tlbConfig.userBits

  val NTLB = tlbConfig.entryNum
  val NTLBBits = log2Up(NTLB)

  val maskLen = vpn0Len + vpn1Len  // 18
  val metaLen = vpnLen + asidLen + maskLen + flagLen // 27 + 16 + 18 + 8 = 69
  val dataLen = ppnLen + AddrBits // 44 + 64 = 108

  val debug = true// && tlbname == "dtlb"

  def metaBundle = new Bundle {
    val vpn = UInt(vpnLen.W)
    val asid = UInt(asidLen.W)
    val mask = UInt(maskLen.W) // to support super page
    val flag = UInt(flagLen.W)
  }

  def dataBundle = new Bundle {
    val ppn = UInt(ppnLen.W)
    val addr = UInt(AddrBits.W) // pte addr, used to write back pte when flag changes (flag.d, flag.v)
  }

}

sealed abstract class TlbBundle(implicit tlbConfig: TLBConfig) extends Bundle with HasNOOPParameter with HasTlbConst with Sv39Const
sealed abstract class TlbModule(implicit tlbConfig: TLBConfig) extends Module with HasNOOPParameter with HasTlbConst with Sv39Const with HasCSRConst

class TLBMeta(implicit val tlbConfig: TLBConfig) extends TlbModule { //TODO: another try that treats TLBMeta as Bunlde not Module
  val io = IO(new Bundle {
    val metas = Output(Vec(NTLB, UInt(metaLen.W)))

    val write = new Bundle {
      val wen = Input(Bool())
      val dest = Input(UInt(NTLBBits.W))
      val vpn = Input(UInt(vpnLen.W))
      val asid = Input(UInt(asidLen.W))
      val mask = Input(UInt(maskLen.W))
      val flag = Input(UInt(flagLen.W))
    }
  })

  val metas = Reg(Vec(NTLB, UInt(metaLen.W)))
  io.metas := metas

  //val reset = WireInit(false.B)
  val resetState = RegInit(true.B)//RegEnable(true.B, init = true.B, reset)
  val (resetSet, resetFinish) = Counter(resetState, NTLB)
  when (resetFinish) { resetState := false.B }

  val writeWen = io.write.wen//WireInit(false.B)
  val writeDest = io.write.dest//WireInit(0.U(NTLBBits.W))
  val writeData = Cat(io.write.vpn, io.write.asid, io.write.mask, io.write.flag)//WireInit(0.U(metaLen.W))

  //def read(addr: UInt) : UInt = metas(addr)
  //def write(addr: UInt, en: Bool, vpn: UInt, asid: UInt, mask: UInt, flag: UInt) = {
  //  writeDest := addr; writeWen := en; writeData := Cat(vpn, asid, mask, flag) 
  //}

  val wen = Mux(resetState, true.B, writeWen)
  val dest = Mux(resetState, resetSet, writeDest)
  val data = Mux(resetState, 0.U, writeData)

  when (wen) { metas(dest) := data }

  def rready() = !resetState
  def wready() = !resetState
}

class TLBData(implicit val tlbConfig: TLBConfig) extends TlbModule {

  val io = IO(new Bundle {
    val datas = Output(Vec(NTLB, UInt(dataLen.W)))

    val write = new Bundle {
      val wen = Input(Bool())
      val dest = Input(UInt(NTLBBits.W))
      val ppn = Input(UInt(ppnLen.W))
      val pteaddr = Input(UInt(AddrBits.W))
    }
  })
  
  val datas = Reg(Vec(NTLB, UInt(dataLen.W)))
  io.datas := datas

  when (io.write.wen) { 
    datas(io.write.dest) := Cat(io.write.ppn, io.write.pteaddr) 
  }
  //def read(addr: UInt) : UInt = datas(addr)
  //def write(addr: UInt, en: Bool, ppn: UInt, pteaddr: UInt) = {
  //  when(en) { datas(addr) := Cat(ppn, pteaddr) }
  //}
}

class TLB(implicit val tlbConfig: TLBConfig) extends TlbModule{
  val io = IO(new Bundle {
    val in = Flipped(new SimpleBusUC(userBits = userBits))
    val out = new SimpleBusUC(userBits = userBits)

    val mem = new SimpleBusUC()
    val flush = Input(Bool()) 
    val exu = Flipped(new TLBExuIO)
    val csrMMU = new MMUIO
    val cacheEmpty = Input(Bool())
    val ipf = Output(Bool())
  })

  // tlb exec
  val tlbExec = Module(new TLBExec)
  tlbExec.io.in <> io.in.req
  io.out.req <> tlbExec.io.out
  io.in.resp <> io.out.resp
  tlbExec.io.flush := io.flush
  tlbExec.io.mem <> io.mem
  tlbExec.io.exu <> io.exu
  tlbExec.io.pf <> io.csrMMU
  io.ipf := tlbExec.io.ipf

  // VM enable && io
  val vmEnable = io.exu.satp.asTypeOf(satpBundle).mode === 8.U && (io.csrMMU.priviledgeMode < ModeM)
  tlbExec.io.in.valid := Mux(vmEnable, io.in.req.valid, false.B)
  io.out.req.valid := Mux(vmEnable, tlbExec.io.out.valid, io.in.req.valid)
  io.in.req.ready := Mux(vmEnable, tlbExec.io.in.ready, io.out.req.ready)
  io.out.req.bits := vmMux(userBits, vmEnable, tlbExec.io.out.bits, io.in.req.bits)

  // lsu need dtlb signals
  if(tlbname == "dtlb") {
    val alreadyOutFinish = RegEnable(true.B, init=false.B, tlbExec.io.out.valid && !tlbExec.io.out.ready)
    when(alreadyOutFinish && tlbExec.io.out.fire()) { alreadyOutFinish := false.B}
    val tlbFinish = (tlbExec.io.out.valid && !alreadyOutFinish) || tlbExec.io.pf.isPF()
    BoringUtils.addSource(tlbFinish, "DTLBFINISH")
    BoringUtils.addSource(io.csrMMU.isPF(), "DTLBPF")
    BoringUtils.addSource(vmEnable, "DTLBENABLE")
  }

  // instruction page fault
  if (tlbname == "itlb") {
    when (tlbExec.io.ipf && vmEnable) {
      tlbExec.io.out.ready := io.cacheEmpty && io.in.resp.ready
      io.out.req.valid := false.B
      io.in.resp.valid := true.B
      io.in.resp.bits.rdata := 0.U
      io.in.resp.bits.cmd := SimpleBusCmd.readLast
      io.in.resp.bits.user.map(_ := io.in.req.bits.user.getOrElse(0.U))
    }
  }
}

class TLBExec(implicit val tlbConfig: TLBConfig) extends TlbModule{
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SimpleBusReqBundle(userBits)))
    val out = Decoupled(new SimpleBusReqBundle(userBits))

    val mem = new SimpleBusUC()
    val flush = Input(Bool()) 
    val exu = Flipped(new TLBExuIO)
    val pf = new MMUIO
    val ipf = Output(Bool())
  })

  // meta & data
  val metasTLB = Module(new TLBMeta)
  val datasTLB = Module(new TLBData)
  val metas = metasTLB.io.metas
  val datas = datasTLB.io.datas

  // meta reset
  metasTLB.reset := reset.asBool || io.exu.sfence.valid
  BoringUtils.addSource(io.exu.sfence.valid, "TLBSFENCEVMA")
  Debug(false) {
    when(io.exu.sfence.valid) {
      printf("%d sfence_vma\n", GTimer())
    }
  }

  // lazy renaming
  val req = io.in.bits
  val vpn = req.addr.asTypeOf(vaBundle2).vpn.asTypeOf(vpnBundle)
  val pf = io.pf
  val satp = io.exu.satp.asTypeOf(satpBundle)

  // pf init
  pf.loadPF := false.B
  pf.storePF := false.B
  pf.addr := req.addr

  // check hit or miss
  val hitVec = VecInit(metas.map(m => m.asTypeOf(metaBundle).flag.asTypeOf(flagBundle).v && MaskEQ(m.asTypeOf(metaBundle).mask, m.asTypeOf(metaBundle).vpn, vpn.asUInt))).asUInt
  //val hitVec = 0.U(NTLB.W)
  val hit = io.in.valid && hitVec.orR
  val miss = io.in.valid && !hitVec.orR
  val victimWaymask = if (NTLB > 1) (1.U << LFSR64()(log2Up(NTLB)-1,0)) else "b1".U
  val waymask = Mux(hit, hitVec, victimWaymask)

  // hit
  val hitMeta = Mux1H(waymask, metas).asTypeOf(metaBundle)
  val hitData = Mux1H(waymask, datas).asTypeOf(dataBundle)
  //val hitMeta = 0.U(metaLen.W).asTypeOf(metaBundle)
  //val hitData = 0.U(dataLen.W).asTypeOf(dataBundle)
  val hitFlag = hitMeta.flag.asTypeOf(flagBundle)
  val hitMask = hitMeta.mask
  // hit write back pte.flag
  val hitWB = hit && (!hitFlag.a || !hitFlag.d && req.isWrite())
  val hitRefillFlag = Cat(req.isWrite().asUInt, 1.U(1.W), 0.U(6.W)) | hitFlag.asUInt
  val hitWBStore = RegEnable(Cat(0.U(10.W), hitData.ppn, 0.U(2.W), hitRefillFlag), hitWB)
  // hit permission check
  val hitCheck = hit /*&& hitFlag.v */&& !(pf.priviledgeMode === ModeU && !hitFlag.u) && !(pf.priviledgeMode === ModeS && hitFlag.u && !pf.status_sum)
  val hitExec = hitCheck && hitFlag.x
  val hitLoad = hitCheck && (hitFlag.r || pf.status_mxr && hitFlag.x)
  val hitStore = hitCheck && hitFlag.w
  
  val isAMO = WireInit(false.B)
  if (tlbname == "dtlb") {
    BoringUtils.addSink(isAMO, "ISAMO")
  }

  val hitinstrPF = WireInit(false.B)
  if (tlbname == "itlb") { hitinstrPF := !hitExec  && hit}
  if (tlbname == "dtlb") { 
    pf.loadPF := !hitLoad && req.isRead() && hit && !isAMO
    pf.storePF := (!hitStore && req.isWrite() && hit) || (!hitLoad && req.isRead() && hit && isAMO)
  }

  // miss
  val s_idle :: s_memReadReq :: s_memReadResp :: s_write_pte :: s_wait_resp :: Nil = Enum(5)
  val state = RegInit(s_idle)
  val level = RegInit(Level.U(log2Up(Level).W))
  
  val memRespStore = Reg(UInt(XLEN.W))
  val missMask = WireInit("h3ffff".U(maskLen.W))
  val missMaskStore = Reg(UInt(maskLen.W))
  val missMetaRefill = WireInit(false.B)
  val missRefillFlag = WireInit(0.U(8.W))
  val memRdata = io.mem.resp.bits.rdata.asTypeOf(pteBundle)
  val raddr = Reg(UInt(AddrBits.W))
  
  //handle flush
  val needFlush = RegInit(false.B)
  val isFlush = needFlush || io.flush
  when (io.flush && (state =/= s_idle)) { needFlush := true.B}
  when (io.out.fire() && needFlush) { needFlush := false.B}

  val missIPF = RegInit(false.B)

  // state machine to handle miss(ptw) and pte-writing-back
  switch (state) {
    is (s_idle) {
      when (!io.flush && hitWB) {
        state := s_write_pte
        needFlush := false.B
      }.elsewhen (miss && !io.flush) {
        state := s_memReadReq
        raddr := paddrApply(satp.ppn, vpn.vpn2) //
        level := Level.U
        needFlush := false.B
      }
    }

    is (s_memReadReq) { 
      when (isFlush) {
        state := s_idle
        needFlush := false.B
      }.elsewhen (io.mem.req.fire()) { state := s_memReadResp}
    }

    is (s_memReadResp) { 
      val missflag = memRdata.flag.asTypeOf(flagBundle)
      when (io.mem.resp.fire()) {
        when (isFlush) {
          state := s_idle
          needFlush := false.B
        }.elsewhen (!(missflag.r || missflag.x)/*!missflag.r && !missflag.x && !missflag.w*/ && (level===3.U || level===2.U)) {
          when(!missflag.v || (!missflag.r && missflag.w)) { //TODO: fix needflush
            if(tlbname == "itlb") { state := s_wait_resp } else { state := s_idle }
            if(tlbname == "itlb") { missIPF := true.B }
            if(tlbname == "dtlb") { 
              pf.loadPF := req.isRead() && !isAMO 
              pf.storePF := req.isWrite() || isAMO 
            }  
            Debug() {
              if(debug) {
                printf("%d " + tlbname +" tlbException!!! ", GTimer())
                printf(p" req:${req}  Memreq:${io.mem.req}  MemResp:${io.mem.resp}")
                printf(" level:%d",level)
                printf("\n")
              }
            }
          }.otherwise {
            state := s_memReadReq
            raddr := paddrApply(memRdata.ppn, Mux(level === 3.U, vpn.vpn1, vpn.vpn0))
          }
        }.elsewhen (level =/= 0.U) { //TODO: fix needFlush
          val permCheck = missflag.v && !(pf.priviledgeMode === ModeU && !missflag.u) && !(pf.priviledgeMode === ModeS && missflag.u && !pf.status_sum)
          val permExec = permCheck && missflag.x
          val permLoad = permCheck && (missflag.r || pf.status_mxr && missflag.x)
          val permStore = permCheck && missflag.w
          val updateAD = !missflag.a || (!missflag.d && req.isWrite())
          val updateData = Cat( 0.U(56.W), req.isWrite(), 1.U(1.W), 0.U(6.W) )
          missRefillFlag := Cat(req.isWrite(), 1.U(1.W), 0.U(6.W)) | missflag.asUInt
          memRespStore := io.mem.resp.bits.rdata | updateData 
          if(tlbname == "itlb") {
            when (!permExec) { missIPF := true.B ; state := s_wait_resp}
            .otherwise { 
              state := Mux(updateAD, s_write_pte, s_wait_resp)
              missMetaRefill := true.B
            }
          }
          if(tlbname == "dtlb") {
            when((!permLoad && req.isRead()) || (!permStore && req.isWrite())) { 
              state := s_idle
              pf.loadPF := req.isRead() && !isAMO
              pf.storePF := req.isWrite() || isAMO
            }.otherwise {
              state := Mux(updateAD, s_write_pte, s_wait_resp)
              missMetaRefill := true.B
            }
          }
          missMask := Mux(level===3.U, 0.U(maskLen.W), Mux(level===2.U, "h3fe00".U(maskLen.W), "h3ffff".U(maskLen.W)))
          missMaskStore := missMask
        }
        level := level - 1.U
      }
    }

    is (s_write_pte) {
      when (isFlush) {
        state := s_idle
        needFlush := false.B
      }.elsewhen (io.mem.req.fire()) { state := s_wait_resp }
    }

    is (s_wait_resp) { when (io.out.fire() || io.flush){
      state := s_idle
      missIPF := false.B
    }}
  }

  // mem
  val cmd = Mux(state === s_write_pte, SimpleBusCmd.write, SimpleBusCmd.read)
  io.mem.req.bits.apply(addr = Mux(hitWB, hitData.addr, raddr), cmd = cmd, size = (if (XLEN == 64) "b11".U else "b10".U), wdata =  Mux( hitWB, hitWBStore, memRespStore), wmask = 0xff.U)
  io.mem.req.valid := ((state === s_memReadReq || state === s_write_pte) && !isFlush)
  io.mem.resp.ready := true.B

  // meta & data refill
  val wdest = 0.U//OHToUInt(waymask)
  //metasTLB.write(addr = wdest, en = (missMetaRefill && !isFlush) || (hitWB && state === s_idle && !isFlush), vpn = vpn.asUInt, asid = Mux(hitWB, hitMeta.asid, satp.asid), mask = Mux(hitWB, hitMask, missMask), flag = Mux(hitWB, hitRefillFlag, missRefillFlag))
  metasTLB.io.write.wen := (missMetaRefill && !isFlush) || (hitWB && state === s_idle && !isFlush)
  metasTLB.io.write.dest := wdest
  metasTLB.io.write.vpn := vpn.asUInt
  metasTLB.io.write.asid := Mux(hitWB, hitMeta.asid, satp.asid)
  metasTLB.io.write.mask := Mux(hitWB, hitMask, missMask)
  metasTLB.io.write.flag := Mux(hitWB, hitRefillFlag, missRefillFlag)
  //datasTLB.write(addr = wdest, en = missMetaRefill && !isFlush, ppn = memRdata.ppn, pteaddr = raddr)
  datasTLB.io.write.wen :=  missMetaRefill && !isFlush
  datasTLB.io.write.dest := wdest
  datasTLB.io.write.ppn := memRdata.ppn
  datasTLB.io.write.pteaddr := raddr

  // io
  io.out.bits := req
  io.out.bits.addr := Mux(hit, maskPaddr(hitData.ppn, req.addr, hitMask), maskPaddr(memRespStore.asTypeOf(pteBundle).ppn, req.addr, missMaskStore))
  io.out.valid := Mux(hit && !hitWB, true.B, state === s_wait_resp)
  
  io.in.ready := io.out.ready && (state === s_idle) && !miss && !hitWB //must be wrong, but unknown how to do

  io.ipf := Mux(hit, hitinstrPF, missIPF)
}

object TLB {
  def apply(in: SimpleBusUC, mem: SimpleBusUC, flush: Bool, exu: TLBExuIO, csrMMU: MMUIO)(implicit tlbConfig: TLBConfig) = {
    val tlb = Module(new TLB)
    tlb.io.in <> in
    tlb.io.mem <> mem
    tlb.io.flush := flush
    tlb.io.exu <> exu
    tlb.io.csrMMU <> csrMMU
    tlb
  }
}

object TLBOpType {
  def vma = "b0".U
}

class fuTlb extends NOOPModule {
  val io = IO(new Bundle{
    val cfIn = Flipped(new CtrlFlowIO)
    val redirect = new RedirectIO
    val valid = Input(Bool())
  })

  io.redirect.valid := io.valid
  io.redirect.target := io.cfIn.pc + 4.U
}

object fuTlb {
  def apply(cf : CtrlFlowIO, valid : Bool) = {
    val futlb = Module(new fuTlb)
    futlb.io.cfIn <> cf
    futlb.io.valid := valid
    futlb.io.redirect
  }
}