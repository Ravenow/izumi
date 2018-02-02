package com.github.pshirshov.izumi.fundamentals.platform.serialization

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import java.nio.ByteBuffer

import scala.language.implicitConversions


class IzSerializable(o: Serializable) {
  def toByteBuffer: ByteBuffer = {
    val byteArrayOutputStream = new ByteArrayOutputStream()
    val objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)
    objectOutputStream.writeObject(o)
    ByteBuffer.wrap(byteArrayOutputStream.toByteArray)
  }
}

object IzSerializable {
  implicit def toRich(s: Serializable): IzSerializable = new IzSerializable(s)

  implicit def toRich(bytes: ByteBuffer): IzByteBuffer = new IzByteBuffer(bytes)

  implicit def toRich(bytes: Array[Byte]): IzByteArray = new IzByteArray(bytes)

}