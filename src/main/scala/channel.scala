package OpenSoC

import Chisel._

abstract class FlitCommon(val parms: Parameters) extends Bundle {
	val packetIDWidth   = parms.get[Int]("packetIDWidth")
	val numVCs          = parms.get[Int]("numVCs")

	val packetID        = UInt(width = packetIDWidth)
	val isTail          = Bool()
	
	val vcPort          = UInt(width = log2Up(numVCs))

}

class HeadFlit(parms: Parameters) extends FlitCommon(parms) {
	val packetTypeWidth   = parms.get[Int]("packetTypeWidth")
	val destCordDim       = parms.get[Int]("destCordDim")
	val destCordWidth     = parms.get[Int]("destCordWidth")
	val numPriorityLevels = parms.get[Int]("numPriorityLevels") 
	
	val packetType        = UInt(width = packetTypeWidth)
	val destination       = Vec(destCordDim, UInt(width = destCordWidth))
	val priorityLevel     = UInt(width = log2Up(numPriorityLevels))

	/*
	val RoutingMode = UInt(width = RoutingModeWidth)
	// Possible Extentions
	//	Error Detection/Correction
	*/

	override def cloneType = { new HeadFlit(parms).asInstanceOf[this.type] }
}

class BodyFlit(parms: Parameters) extends FlitCommon(parms) {
	val flitIDWidth     = parms.get[Int]("flitIDWidth")
	val payloadWidth    = parms.get[Int]("payloadWidth")
	

	val flitID          = UInt(width = flitIDWidth)
	val payload         = UInt(width = payloadWidth)

	override def cloneType = { new BodyFlit(parms).asInstanceOf[this.type] }
}

class Flit(parms: Parameters) extends Bundle {
	val union   = new BitUnion(Map("Head" -> new HeadFlit(parms), "Body" -> new BodyFlit(parms)))
	val x       = Chisel.UInt(width = union.width)
	val numVCs  = parms.get[Int]("numVCs")

	def asHead(dummy: Int = 0) : HeadFlit = union.unpack[HeadFlit]("Head", x)
	def asBody(dummy: Int = 0) : BodyFlit = union.unpack[BodyFlit]("Body", x)

	def whenHead(block: HeadFlit => Unit) { union.whenTag[HeadFlit]("Head", x)(block) }
	def whenBody(block: BodyFlit => Unit) { union.whenTag[BodyFlit]("Body", x)(block) }

	def isHead(dummy: Int = 0) : Bool = union.tagEquals("Head", x)
	def isBody(dummy: Int = 0) : Bool = union.tagEquals("Body", x)
	def isTail(dummy: Int = 0) : Bool = {
		val tailBit = Wire(Bool())
		when (isHead()) {
			tailBit := union.unpack[HeadFlit]("Head", x).isTail
		} .otherwise {
			tailBit := union.unpack[BodyFlit]("Body", x).isTail
		}
		tailBit
	}
	def getVCPort(dummy: Int = 0) : UInt = {
		val vcBits = Wire(UInt(width = log2Up(numVCs)))
		when (isHead()) {
			vcBits := union.unpack[HeadFlit]("Head", x).vcPort
		} .otherwise {
			vcBits := union.unpack[BodyFlit]("Body", x).vcPort
		}
		vcBits
	}

	override def cloneType = { new Flit(parms).asInstanceOf[this.type] }
	// override def width : Int = {x.width}
}

object Flit {
	def head(h: HeadFlit) : Flit = {
		val f = Wire(new Flit(h.parms))
		f.x := f.union.pack("Head", h)
		f
	}

	def body(b: BodyFlit) : Flit = {
		val f = Wire(new Flit(b.parms))
		f.x := f.union.pack("Body", b)
		f
	}

	def fromBits(n: UInt, parms: Parameters) : Flit = {
		val f = Wire(new Flit(parms))
		f.x := n
		f
	}
	
	/*
	def zeroHead(parms: Parameters) : HeadFlit = {
		val x = new HeadFlit(parms)
		x.
	}
	*/
}

class ChannelVC(parms: Parameters) extends Bundle {
	val numVCs  = parms.get[Int]("numVCs")
	
	val flit    	= new Flit(parms).asInput
	val flitValid	= Bool(INPUT)
	val credit  	= Vec(numVCs, new Credit() ) // Direction as Output in class def

  override def cloneType = { new ChannelVC(parms).asInstanceOf[this.type] }
}

class Channel(parms: Parameters) extends Bundle {
	val flit		= new Flit(parms).asInput
	val flitValid	= Bool(INPUT)
	val credit		= new Credit() // Direction as Output in class def
	
  override def cloneType = { new Channel(parms).asInstanceOf[this.type] }
}

class ReplaceVCPort(parms: Parameters) extends Module(parms) {
	val numVCs = parms.get[Int]("numVCs")

	val io   = new Bundle {
		val oldFlit     = new Flit(parms).asInput
		val newVCPort   = UInt(INPUT, log2Up(numVCs))
		val newFlit     = new Flit(parms).asOutput
	}

	val h           = Wire(new HeadFlit(parms))
	h.packetID      := io.oldFlit.asHead().packetID
	h.isTail        := io.oldFlit.asHead().isTail
	h.vcPort        := io.newVCPort
	h.packetType    := io.oldFlit.asHead().packetType
	h.destination.zipWithIndex.foreach{ case (e,i) => e := io.oldFlit.asHead().destination(i) }

	val b           = Wire(new BodyFlit(parms))
	b.packetID      := io.oldFlit.asBody().packetID
	b.isTail        := io.oldFlit.asBody().isTail
	b.vcPort        := io.newVCPort

	b.flitID        := io.oldFlit.asBody().flitID
	b.payload       := io.oldFlit.asBody().payload

	val flitVCMux = Chisel.Module( new MuxN[Flit](
		new Flit(parms), parms.child("FlitVCMux", Map(
			("n"->Soft(2))
		))
	))

	flitVCMux.io.ins(1) := Flit.head(h)
	flitVCMux.io.ins(0) := Flit.body(b)
	flitVCMux.io.sel    := io.oldFlit.isHead()
	io.newFlit          := flitVCMux.io.out

}
/*
class UpdateBreadCrumb(parms: Parameters) extends Module(parms) {

	val destCordDim     = parms.get[Int]("destCordDim")
	val destCordWidth   = parms.get[Int]("destCordWidth")
	val io = new Bundle {
		val oldFlit         = new Flit(parms).asInput
		val routerID        = Vec(destCordDim, UInt(width = destCordWidth)).asInput
        val routerIDValid   = UInt(INPUT, width=1)
		val newFlit         = new Flit(parms).asOutput
	}

	val h           = new HeadFlit(parms)
    //h.breadCrumbIndex := UInt(0)
	h.packetID      := io.oldFlit.asHead().packetID
	h.isTail        := io.oldFlit.asHead().isTail
	h.vcPort        := io.oldFlit.asHead().vcPort
	h.packetType    := io.oldFlit.asHead().packetType
	h.destination.zipWithIndex.foreach{ case (e,i) => e := io.oldFlit.asHead().destination(i) }
    for (i <- 0 until h.breadCrumb.size){h.breadCrumb(i) := Vec(destCordDim, UInt(0))}

     //   h.breadCrumb                    := io.oldFlit.asHead().breadCrumb
     //   h.breadCrumbIndex               := io.oldFlit.asHead().breadCrumbIndex
    
    when(io.routerIDValid === UInt(1)){
        h.breadCrumb(h.breadCrumbIndex) := io.routerID
        h.breadCrumbIndex               := io.oldFlit.asHead().breadCrumbIndex + UInt(1)
    }.otherwise{
        h.breadCrumb(h.breadCrumbIndex) := io.oldFlit.asHead().breadCrumb(h.breadCrumbIndex)
        h.breadCrumbIndex               := io.oldFlit.asHead().breadCrumbIndex //h.breadCrumbIndex
    }

	val b = new BodyFlit(parms)
	b.packetID  := io.oldFlit.asBody().packetID
	b.isTail    := io.oldFlit.asBody().isTail
	b.vcPort    := io.oldFlit.asBody().vcPort

	b.flitID    := io.oldFlit.asBody().flitID
	b.payload   := io.oldFlit.asBody().payload

	val flitVCMux = Chisel.Module( new MuxN[Flit](
		new Flit(parms), parms.child("FlitVCMux", Map(
			("n"->Soft(2))
		))
	))

	flitVCMux.io.ins(1) := Flit.head(h)
	flitVCMux.io.ins(0) := Flit.body(b)
	flitVCMux.io.sel    := io.oldFlit.isHead()
	io.newFlit          := flitVCMux.io.out

}*/
