package org.http4s.blaze.channel.nio1

import java.io.IOException
import java.nio.channels._
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.{RejectedExecutionException, ThreadFactory}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import org.http4s.blaze.channel.{
  BufferPipelineBuilder,
  ChannelOptions,
  DefaultPoolSize,
  ServerChannel,
  ServerChannelGroup
}
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.BasicThreadFactory
import org.log4s._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Try
import scala.util.control.NonFatal

object NIO1SocketServerGroup {

  /** Default size of buffer to use in a [[SelectorLoop]] */
  private[this] val DefaultBufferSize: Int = 64 * 1024

  private[this] val factoryNumber = new AtomicInteger(0)
  // Default `ThreadFactory` implementation for selector threads
  private[this] def defaultAcceptThreadFactory: ThreadFactory = {
    val id = factoryNumber.getAndIncrement()
    BasicThreadFactory(prefix = s"blaze-selector-$id", daemonThreads = false)
  }

  /** Create a new [[NIO1SocketServerGroup]] from the [[SelectorLoopPool]].
    *
    * @note The worker pool is not owned by the group and therefore not
    *       shutdown when the group is shutdown.
    */
  def apply(
      workerPool: SelectorLoopPool,
      channelOptions: ChannelOptions = ChannelOptions.DefaultOptions
  ): ServerChannelGroup =
    new NIO1SocketServerGroup(workerPool, channelOptions)

  /** Create a new [[NIO1SocketServerGroup]] with a fresh [[FixedSelectorPool]]
    *
    * The resulting [[ServerChannelGroup]] takes ownership of the created pool,
    * shutting it down when the group is shutdown.
    */
  def fixedGroup(
      workerThreads: Int = DefaultPoolSize,
      bufferSize: Int = DefaultBufferSize,
      channelOptions: ChannelOptions = ChannelOptions.DefaultOptions,
      selectorThreadFactory: ThreadFactory = defaultAcceptThreadFactory
  ): ServerChannelGroup = {

    val pool = new FixedSelectorPool(workerThreads, bufferSize, selectorThreadFactory)
    val underlying = apply(pool, channelOptions)

    // Proxy to the underlying group. `close` calls also close
    // the worker pools since we were the ones that created it.
    new ServerChannelGroup {
      override def closeGroup(): Unit = {
        // Closing the underlying group will schedule their
        // shutdown tasks so even though they will be executed
        // asynchronously, they will be handled before the loops
        // shutdown since they cleanup pending tasks before dying
        // themselves.
        underlying.closeGroup()
        pool.close()
      }

      override def bind(
          address: InetSocketAddress,
          service: BufferPipelineBuilder
      ): Try[ServerChannel] =
        underlying.bind(address, service)
    }
  }
}

/** A thread resource group for NIO1 network operations
  *
  * @param selectorPool [[SelectorLoopPool]] that will belong to this group. The group
  *                    assumes responsibility for shutting it down. Shutting down the
  *                    pool after giving it to this group will result in undefined behavior.
  */
private final class NIO1SocketServerGroup private (
    selectorPool: SelectorLoopPool,
    channelOptions: ChannelOptions)
    extends ServerChannelGroup {

  private[this] val logger = getLogger
  // Also acts as our intrinsic lock.
  private[this] val listeningSet = new mutable.HashSet[ServerChannelImpl]()
  // protected by synchronization on the intrinsic lock.
  private[this] var isClosed = false

  // Closing delegates to the `ServerChannelImpl` which
  // ensures only-once behavior and attempts to close the
  // channel within the `SelectorLoop`, if it's still running.
  // That should help alleviate many of the exceptions associated
  // with performing operations on closed channels, etc.
  private[this] class SocketAcceptor(
      key: SelectionKey,
      ch: ServerChannelImpl,
      service: BufferPipelineBuilder)
      extends Selectable {

    // Save it since once the channel is closed, we're in trouble.
    private[this] val closed = new AtomicBoolean(false)

    override def opsReady(unused: ByteBuffer): Unit =
      if (key.isAcceptable) {
        try acceptNewConnections()
        catch {
          case ex: IOException =>
            closeWithError(ex)
        }
      }

    override def close(): Unit =
      if (closed.compareAndSet(false, true) && !ch.channelClosed) {
        logger.info(s"Listening socket(${ch.socketAddress}) closed.")
        doClose()
      }

    override def closeWithError(cause: Throwable): Unit =
      if (closed.compareAndSet(false, true) && !ch.channelClosed) {
        logger.error(cause)(s"Listening socket(${ch.socketAddress}) closed forcibly.")
        doClose()
      }

    // To close, we close the `ServerChannelImpl` which will
    // close the `ServerSocketChannel`.
    private[this] def doClose(): Unit = ch.close()

    @tailrec
    private[this] def acceptNewConnections(): Unit = {
      // We go in a loop just in case we have more than one.
      // Once we're out, the `.accept()` method will return `null`.
      val child = ch.selectableChannel.accept()
      if (child != null) {
        handleClientChannel(child, service)
        acceptNewConnections()
      }
    }
  }

  // The core type tracked in this group. It is exclusively responsible
  // for closing the underlying `ServerSocketChannel`. IF possible, closing
  // the underlying channel is done in its assigned selector loop to
  // minimize race conditions.
  private[this] final class ServerChannelImpl(
      val selectableChannel: ServerSocketChannel,
      selectorLoop: SelectorLoop)
      extends ServerChannel
      with NIO1Channel {

    @volatile
    private[this] var closed = false

    def channelClosed: Boolean = closed

    val socketAddress: InetSocketAddress =
      selectableChannel.getLocalAddress.asInstanceOf[InetSocketAddress]

    override protected def closeChannel(): Unit = {
      // We try to run the close in the event loop, but just
      // in case we were closed because the event loop was closed,
      // we need to be ready to handle a `RejectedExecutionException`.
      def doClose(): Unit = {
        logger.info(s"Closing NIO1 channel $socketAddress")
        closed = true
        listeningSet.synchronized {
          listeningSet.remove(this)
        }
        try selectableChannel.close()
        catch {
          case NonFatal(t) => logger.warn(t)("Failure during channel close.")
        }
      }

      try {
        // We use `enqueueTask` deliberately so as to not jump ahead
        // of channel initialization.
        selectorLoop.enqueueTask(new Runnable {
          override def run(): Unit = doClose()
        })
      } catch {
        case _: RejectedExecutionException =>
          logger.info("Selector loop closed. Closing in local thread.")
          doClose()
      }
    }
  }

  override def closeGroup(): Unit = {
    // Set the state to closed and close any existing acceptors
    val toClose = listeningSet.synchronized {
      if (isClosed) Seq.empty
      else {
        isClosed = true
        val toClose = listeningSet.toVector
        listeningSet.clear()
        toClose
      }
    }
    toClose.foreach(_.close())
  }

  /** Create a [[org.http4s.blaze.channel.ServerChannel]] that will serve the
    * services on the requisite sockets */
  override def bind(
      address: InetSocketAddress,
      service: BufferPipelineBuilder
  ): Try[ServerChannel] = Try {
    val ch = ServerSocketChannel.open().bind(address)
    ch.configureBlocking(false)
    val loop = selectorPool.nextLoop()

    val serverChannel = new ServerChannelImpl(ch, loop)
    val closed = listeningSet.synchronized {
      if (isClosed) true
      else {
        listeningSet += serverChannel
        false
      }
    }

    if (closed) {
      logger.info("Group closed")
      serverChannel.close()
    } else {
      logger.info("Service bound to address " + serverChannel.socketAddress)
      loop.initChannel(serverChannel, buildSocketAcceptor(serverChannel, service))
    }

    serverChannel
  }

  // Will be called from within the SelectorLoop
  private[this] def buildSocketAcceptor(
      ch: ServerChannelImpl,
      service: BufferPipelineBuilder
  )(key: SelectionKey): Selectable = {
    val acceptor = new SocketAcceptor(key, ch, service)
    try key.interestOps(SelectionKey.OP_ACCEPT)
    catch {
      case ex: CancelledKeyException => acceptor.closeWithError(ex)
    }
    acceptor
  }

  private[this] def handleClientChannel(
      clientChannel: SocketChannel,
      service: BufferPipelineBuilder
  ): Unit = {
    val address = clientChannel.getRemoteAddress.asInstanceOf[InetSocketAddress]
    // Check to see if we want to keep this connection.
    // Note: we only log at trace since it is highly likely that `acceptConnection`
    // would have also logged this info for normal application observability.
    if (acceptConnection(address)) {
      clientChannel.configureBlocking(false)
      logger.trace(s"Accepted connection from $address")
      channelOptions.applyToChannel(clientChannel)
      val loop = selectorPool.nextLoop()
      loop.initChannel(
        NIO1Channel(clientChannel),
        key => {
          val conn = NIO1Connection(clientChannel)
          val head = new SocketChannelHead(clientChannel, loop, key)
          service(conn).base(head)
          head.inboundCommand(Command.Connected)
          head
        }
      )
    } else {
      logger.trace(s"Rejected connection from $address")
      clientChannel.close()
    }
  }
}
