/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address
import scala.concurrent.duration._
import scala.collection.immutable
import akka.remote.FailureDetector
import akka.remote.DefaultFailureDetectorRegistry
import scala.concurrent.forkjoin.ThreadLocalRandom

object ClusterHeartbeatSenderStateSpec {
  class FailureDetectorStub extends FailureDetector {

    trait Status
    object Up extends Status
    object Down extends Status
    object Unknown extends Status

    private var status: Status = Unknown

    def markNodeAsUnavailable(): Unit = status = Down

    def markNodeAsAvailable(): Unit = status = Up

    override def isAvailable: Boolean = status match {
      case Unknown | Up ⇒ true
      case Down         ⇒ false
    }

    override def isMonitoring: Boolean = status != Unknown

    override def heartbeat(): Unit = status = Up

  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterHeartbeatSenderStateSpec extends WordSpec with Matchers {
  import ClusterHeartbeatSenderStateSpec._

  val aa = UniqueAddress(Address("akka.tcp", "sys", "aa", 2552), 1)
  val bb = UniqueAddress(Address("akka.tcp", "sys", "bb", 2552), 2)
  val cc = UniqueAddress(Address("akka.tcp", "sys", "cc", 2552), 3)
  val dd = UniqueAddress(Address("akka.tcp", "sys", "dd", 2552), 4)
  val ee = UniqueAddress(Address("akka.tcp", "sys", "ee", 2552), 5)

  def emptyState: ClusterHeartbeatSenderState = emptyState(aa)

  def emptyState(selfUniqueAddress: UniqueAddress) = ClusterHeartbeatSenderState(
    ring = HeartbeatNodeRing(selfUniqueAddress, Set(selfUniqueAddress), monitoredByNrOfMembers = 3),
    unreachable = Set.empty[UniqueAddress],
    failureDetector = new DefaultFailureDetectorRegistry[Address](() ⇒ new FailureDetectorStub))

  def fd(state: ClusterHeartbeatSenderState, node: UniqueAddress): FailureDetectorStub =
    state.failureDetector.asInstanceOf[DefaultFailureDetectorRegistry[Address]].failureDetector(node.address).
      get.asInstanceOf[FailureDetectorStub]

  "A ClusterHeartbeatSenderState" must {

    "return empty active set when no nodes" in {
      emptyState.activeReceivers.isEmpty should be(true)
    }

    "init with empty" in {
      emptyState.init(Set.empty).activeReceivers should be(Set.empty)
    }

    "init with self" in {
      emptyState.init(Set(aa, bb, cc)).activeReceivers should be(Set(bb, cc))
    }

    "init without self" in {
      emptyState.init(Set(bb, cc)).activeReceivers should be(Set(bb, cc))
    }

    "use added members" in {
      emptyState.addMember(bb).addMember(cc).activeReceivers should be(Set(bb, cc))
    }

    "not use removed members" in {
      emptyState.addMember(bb).addMember(cc).removeMember(bb).activeReceivers should be(Set(cc))
    }

    "use specified number of members" in {
      // they are sorted by the hash (uid) of the UniqueAddress
      emptyState.addMember(cc).addMember(dd).addMember(bb).addMember(ee).activeReceivers should be(Set(bb, cc, dd))
    }

    "update failure detector in active set" in {
      val s1 = emptyState.addMember(bb).addMember(cc).addMember(dd)
      val s2 = s1.heartbeatRsp(bb).heartbeatRsp(cc).heartbeatRsp(dd).heartbeatRsp(ee)
      s2.failureDetector.isMonitoring(bb.address) should be(true)
      s2.failureDetector.isMonitoring(cc.address) should be(true)
      s2.failureDetector.isMonitoring(dd.address) should be(true)
      s2.failureDetector.isMonitoring(ee.address) should be(false)
    }

    "continue to use unreachable" in {
      val s1 = emptyState.addMember(cc).addMember(dd).addMember(ee)
      val s2 = s1.heartbeatRsp(cc).heartbeatRsp(dd).heartbeatRsp(ee)
      fd(s2, ee).markNodeAsUnavailable()
      s2.failureDetector.isAvailable(ee.address) should be(false)
      s2.addMember(bb).activeReceivers should be(Set(bb, cc, dd, ee))
    }

    "remove unreachable when coming back" in {
      val s1 = emptyState.addMember(cc).addMember(dd).addMember(ee)
      val s2 = s1.heartbeatRsp(cc).heartbeatRsp(dd).heartbeatRsp(ee)
      fd(s2, dd).markNodeAsUnavailable()
      fd(s2, ee).markNodeAsUnavailable()
      val s3 = s2.addMember(bb)
      s3.activeReceivers should be(Set(bb, cc, dd, ee))
      val s4 = s3.heartbeatRsp(bb).heartbeatRsp(cc).heartbeatRsp(dd).heartbeatRsp(ee)
      s4.activeReceivers should be(Set(bb, cc, dd))
      s4.failureDetector.isMonitoring(ee.address) should be(false)
    }

    "remove unreachable when member removed" in {
      val s1 = emptyState.addMember(cc).addMember(dd).addMember(ee)
      val s2 = s1.heartbeatRsp(cc).heartbeatRsp(dd).heartbeatRsp(ee)
      fd(s2, cc).markNodeAsUnavailable()
      fd(s2, ee).markNodeAsUnavailable()
      val s3 = s2.addMember(bb).heartbeatRsp(bb)
      s3.activeReceivers should be(Set(bb, cc, dd, ee))
      val s4 = s3.removeMember(cc).removeMember(ee)
      s4.activeReceivers should be(Set(bb, dd))
      s4.failureDetector.isMonitoring(cc.address) should be(false)
      s4.failureDetector.isMonitoring(ee.address) should be(false)
    }

    "behave correctly for random operations" in {
      val rnd = ThreadLocalRandom.current
      val nodes = (1 to rnd.nextInt(10, 200)).map(n ⇒ UniqueAddress(Address("akka.tcp", "sys", "n" + n, 2552), n)).toVector
      def rndNode() = nodes(rnd.nextInt(0, nodes.size))
      val selfUniqueAddress = rndNode()
      var state = emptyState(selfUniqueAddress)
      val Add = 0
      val Remove = 1
      val Unreachable = 2
      val HeartbeatRsp = 3
      for (i ← 1 to 100000) {
        val operation = rnd.nextInt(Add, HeartbeatRsp + 1)
        val node = rndNode()
        try {
          operation match {
            case Add ⇒
              if (node != selfUniqueAddress && !state.ring.nodes.contains(node)) {
                val oldUnreachable = state.unreachable
                state = state.addMember(node)
                // keep unreachable
                (oldUnreachable -- state.activeReceivers) should be(Set.empty)
                state.failureDetector.isMonitoring(node.address) should be(false)
                state.failureDetector.isAvailable(node.address) should be(true)
              }

            case Remove ⇒
              if (node != selfUniqueAddress && state.ring.nodes.contains(node)) {
                val oldUnreachable = state.unreachable
                state = state.removeMember(node)
                // keep unreachable, unless it was the removed
                if (oldUnreachable(node))
                  (oldUnreachable -- state.activeReceivers) should be(Set(node))
                else
                  (oldUnreachable -- state.activeReceivers) should be(Set.empty)

                state.failureDetector.isMonitoring(node.address) should be(false)
                state.failureDetector.isAvailable(node.address) should be(true)
                state.activeReceivers should not contain (node)
              }

            case Unreachable ⇒
              if (node != selfUniqueAddress && state.activeReceivers(node)) {
                state.failureDetector.heartbeat(node.address) // make sure the fd is created
                fd(state, node).markNodeAsUnavailable()
                state.failureDetector.isMonitoring(node.address) should be(true)
                state.failureDetector.isAvailable(node.address) should be(false)
              }

            case HeartbeatRsp ⇒
              if (node != selfUniqueAddress && state.ring.nodes.contains(node)) {
                val oldUnreachable = state.unreachable
                val oldReceivers = state.activeReceivers
                val oldRingReceivers = state.ring.myReceivers
                state = state.heartbeatRsp(node)

                if (oldUnreachable(node))
                  state.unreachable should not contain (node)

                if (oldUnreachable(node) && !oldRingReceivers(node))
                  state.failureDetector.isMonitoring(node.address) should be(false)

                if (oldRingReceivers(node))
                  state.failureDetector.isMonitoring(node.address) should be(true)

                state.ring.myReceivers should be(oldRingReceivers)
                state.failureDetector.isAvailable(node.address) should be(true)

              }

          }
        } catch {
          case e: Throwable ⇒
            println(s"Failure context: i=$i, node=$node, op=$operation, unreachable=${state.unreachable}, " +
              s"ringReceivers=${state.ring.myReceivers}, ringNodes=${state.ring.nodes}")
            throw e
        }
      }

    }

  }
}

