/*
Copyright 2015 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.scalding.serialization

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream, Serializable }

import scala.util.{ Failure, Success, Try }

/**
 * This is a base Input/OutputStream-based serialization typeclass
 * This is useful for value serialization in hadoop when we don't
 * need to do key sorting for partitioning.
 *
 * This serialization typeclass must serialize equivalent objects
 * identically to be lawful.
 */
trait Serialization[T] extends Any with Equiv[T] with Serializable {
  /**
   * A serialization always gives a hash because one can just
   * serialize and then hash the bytes. You might prefer another
   * implementation. This must satisfy:
   *   (!equiv(a, b)) || (hash(a) == hash(b))
   */
  def hash(t: T): Int
  def read(in: InputStream): Try[T]
  def write(out: OutputStream, t: T): Try[Unit]
}

object Serialization {
  import JavaStreamEnrichments._
  /**
   * This is a constant for us to reuse in Serialization.write
   */
  val successUnit: Try[Unit] = Success(())

  def equiv[T](a: T, b: T)(implicit ser: Serialization[T]): Boolean =
    ser.equiv(a, b)

  def hash[T](t: T)(implicit ser: Serialization[T]): Int =
    ser.hash(t)

  def read[T](in: InputStream)(implicit ser: Serialization[T]): Try[T] =
    ser.read(in)

  def write[T](out: OutputStream, t: T)(implicit ser: Serialization[T]): Try[Unit] =
    ser.write(out, t)

  def toBytes[T: Serialization](t: T): Try[Array[Byte]] = {
    val baos = new ByteArrayOutputStream
    write(baos, t).map { _ => baos.toByteArray }
  }

  def fromBytes[T: Serialization](b: Array[Byte]): Try[T] =
    read(new ByteArrayInputStream(b))

  private def roundTrip[T](t: T)(implicit ser: Serialization[T]): T = {
    val baos = new ByteArrayOutputStream
    ser.write(baos, t).get // should never throw on a ByteArrayOutputStream
    ser.read(baos.toInputStream).get
  }

  /**
   * write followed by read should give an equivalent T
   *
   * This is a law that serialization must follow. It is here for
   * documentation and for use within tests without any dependence on
   * specific test frameworks.
   *
   * forAll(roundTripLaw[T]) in a valid test in scalacheck style
   */
  def roundTripLaw[T: Serialization]: Law1[T] =
    Law1("roundTrip", { (t: T) => equiv(roundTrip(t), t) })

  /**
   * If two items are equal, they should serialize byte for byte equivalently
   */
  def serializationIsEquivalence[T: Serialization]: Law2[T] =
    Law2("equiv(a, b) => write(a) == write(b)", { (t1: T, t2: T) =>
      (!equiv(t1, t2)) || {
        java.util.Arrays.equals(toBytes(t1).get, toBytes(t2).get)
      }
    })

  def hashCodeImpliesEquality[T: Serialization]: Law2[T] =
    Law2("equiv(a, b) => hash(a) == hash(b)", { (t1: T, t2: T) =>
      (!equiv(t1, t2)) || (hash(t1) == hash(t2))
    })

  def reflexivity[T: Serialization]: Law1[T] =
    Law1("equiv(a, a) == true", { (t1: T) => equiv(t1, t1) })

  def allLaws[T: Serialization]: Iterable[Law[T]] =
    List(roundTripLaw, serializationIsEquivalence, hashCodeImpliesEquality, reflexivity)
}
