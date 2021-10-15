package sttp.tapir.server.netty

import io.netty.channel._
import sttp.monad.{FutureMonad, MonadError}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyOptionsBuilder.{DomainSocketOptionsBuilder, TcpOptionsBuilder}
import sttp.tapir.server.netty.internal.FutureUtil._
import sttp.tapir.server.netty.internal.{NettyBootstrap, NettyServerHandler}

import java.net.InetSocketAddress
import scala.concurrent.{ExecutionContext, Future}

case class NettyFutureServer(routes: Vector[FutureRoute], options: NettyFutureServerOptions)(implicit ec: ExecutionContext) {
  def addEndpoint(se: ServerEndpoint[_, _, _, Any, Future]): NettyFutureServer = addEndpoints(List(se))
  def addEndpoint(se: ServerEndpoint[_, _, _, Any, Future], overrideOptions: NettyFutureServerOptions): NettyFutureServer =
    addEndpoints(List(se), overrideOptions)
  def addEndpoints(ses: List[ServerEndpoint[_, _, _, Any, Future]]): NettyFutureServer = addRoute(
    NettyFutureServerInterpreter(options).toRoute(ses)
  )
  def addEndpoints(ses: List[ServerEndpoint[_, _, _, Any, Future]], overrideOptions: NettyFutureServerOptions): NettyFutureServer =
    addRoute(
      NettyFutureServerInterpreter(overrideOptions).toRoute(ses)
    )

  def addRoute(r: FutureRoute): NettyFutureServer = copy(routes = routes :+ r)
  def addRoutes(r: Iterable[FutureRoute]): NettyFutureServer = copy(routes = routes ++ r)

  def start(): Future[NettyFutureServerBinding] = {
    val eventLoopGroup = options.nettyOptions.eventLoopConfig.builder()
    implicit val monadError: MonadError[Future] = new FutureMonad()
    val route = Route.combine(routes)

    val channelFuture = NettyBootstrap(
      options.nettyOptions,
      new NettyServerHandler(route, (f: Future[Unit]) => f),
      eventLoopGroup,
      options.nettyOptions.eventLoopConfig.serverChannel,
      options.nettyOptions.socketAddress
    )

    nettyChannelFutureToScala(channelFuture).map(ch =>
      NettyFutureServerBinding(
        ch.localAddress().asInstanceOf[InetSocketAddress],
        () => stop(ch, eventLoopGroup)
      )
    )
  }

  private def stop(ch: Channel, eventLoopGroup: EventLoopGroup): Future[Unit] = {
    nettyFutureToScala(ch.close()).flatMap { _ =>
      if (options.nettyOptions.shutdownEventLoopGroupOnClose) {
        nettyFutureToScala(eventLoopGroup.shutdownGracefully()).map(_ => ())
      } else Future.successful(())
    }
  }
}

object NettyFutureServer {
  def apply(serverOptions: NettyFutureServerOptions = NettyFutureServerOptions.default)(implicit ec: ExecutionContext): NettyFutureServer =
    NettyFutureServer(Vector.empty, serverOptions)

  def tcp(f: TcpOptionsBuilder => TcpOptionsBuilder = identity)(implicit ec: ExecutionContext): NettyFutureServer = {
    NettyFutureServer(Vector.empty, NettyFutureServerOptions.default.copy(nettyOptions = f(NettyOptionsBuilder.make().tcp()).build))
  }

  def unixDomainSocket(f: DomainSocketOptionsBuilder => DomainSocketOptionsBuilder = identity)(implicit ec: ExecutionContext): NettyFutureServer = {
    NettyFutureServer(Vector.empty, NettyFutureServerOptions.default.copy(nettyOptions = f(NettyOptionsBuilder.make().domainSocket()).build))
  }
}

case class NettyServerOptionsBuilder(options: NettyFutureServerOptions) {

}

case class NettyFutureServerBinding(localSocket: InetSocketAddress, stop: () => Future[Unit]) {
  def host: String = localSocket.getHostString
  def port: Int = localSocket.getPort
}