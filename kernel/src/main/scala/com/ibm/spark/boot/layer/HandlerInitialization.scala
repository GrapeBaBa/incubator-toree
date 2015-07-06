/*
 * Copyright 2014 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ibm.spark.boot.layer

import akka.actor.{ActorRef, ActorSystem, Props}
import com.ibm.spark.comm.{CommRegistrar, CommStorage}
import com.ibm.spark.interpreter.Interpreter
import com.ibm.spark.kernel.protocol.v5.MessageType.MessageType
import com.ibm.spark.kernel.protocol.v5.SocketType.SocketType
import com.ibm.spark.kernel.protocol.v5.handler._
import com.ibm.spark.kernel.protocol.v5.interpreter.InterpreterActor
import com.ibm.spark.kernel.protocol.v5.interpreter.tasks.InterpreterTaskFactory
import com.ibm.spark.kernel.protocol.v5.kernel.ActorLoader
import com.ibm.spark.kernel.protocol.v5.magic.{MagicParser, PostProcessor}
import com.ibm.spark.kernel.protocol.v5.relay.ExecuteRequestRelay
import com.ibm.spark.kernel.protocol.v5.{MessageType, SocketType, SystemActorType}
import com.ibm.spark.magic.MagicLoader
import com.ibm.spark.utils.LogLike
import com.typesafe.config.Config

/**
 * Represents the Akka handler initialization. All actors (not needed in bare
 * initialization) should be constructed here.
 */
trait HandlerInitialization {
  /**
   * Initializes and registers all handlers.
   *
   * @param config The configuration associated with the kernel
   * @param actorSystem The actor system needed for registration
   * @param actorLoader The actor loader needed for registration
   * @param interpreter The main interpreter needed for registration
   * @param magicLoader The magic loader needed for registration
   * @param commRegistrar The comm registrar needed for registration
   * @param commStorage The comm storage needed for registration
   */
  def initializeHandlers(
    config: Config,
    actorSystem: ActorSystem, actorLoader: ActorLoader,
    interpreter: Interpreter, magicLoader: MagicLoader,
    commRegistrar: CommRegistrar, commStorage: CommStorage,
    responseMap: collection.mutable.Map[String, ActorRef]
  ): Unit
}

/**
 * Represents the standard implementation of HandlerInitialization.
 */
trait StandardHandlerInitialization extends HandlerInitialization {
  this: LogLike =>

  /**
   * Initializes and registers all handlers.
   *
   * @param config The configuration associated with the kernel
   * @param actorSystem The actor system needed for registration
   * @param actorLoader The actor loader needed for registration
   * @param interpreter The main interpreter needed for registration
   * @param magicLoader The magic loader needed for registration
   * @param commRegistrar The comm registrar needed for registration
   * @param commStorage The comm storage needed for registration
   */
  def initializeHandlers(
    config: Config,
    actorSystem: ActorSystem, actorLoader: ActorLoader,
    interpreter: Interpreter, magicLoader: MagicLoader,
    commRegistrar: CommRegistrar, commStorage: CommStorage,
    responseMap: collection.mutable.Map[String, ActorRef]
  ): Unit = {
    initializeKernelHandlers(
      actorSystem, actorLoader, commRegistrar, commStorage, responseMap)
    initializeSystemActors(
      config,
      actorSystem,
      actorLoader,
      interpreter,
      magicLoader
    )
  }

  private def initializeSystemActors(
    config: Config,
    actorSystem: ActorSystem, actorLoader: ActorLoader,
    interpreter: Interpreter, magicLoader: MagicLoader
  ): Unit = {
    val captureStandardOut = config.getBoolean("capture_standard_out")
    val captureStandardErr = config.getBoolean("capture_standard_err")

    logger.info(Seq(
      "Creating interpreter actor",
      if (captureStandardOut) "[capturing standard out]" else "",
      if (captureStandardErr) "[capturing standard err]" else ""
    ).mkString(" "))
    val interpreterActor = actorSystem.actorOf(
      Props(
        classOf[InterpreterActor],
        new InterpreterTaskFactory(interpreter),
        captureStandardOut,
        captureStandardErr
      ),
      name = SystemActorType.Interpreter.toString
    )

    logger.debug("Creating execute request relay actor")
    val postProcessor = new PostProcessor(interpreter)
    val magicParser = new MagicParser(magicLoader)
    val executeRequestRelayActor = actorSystem.actorOf(
      Props(classOf[ExecuteRequestRelay],
        actorLoader, magicLoader, magicParser, postProcessor
      ),
      name = SystemActorType.ExecuteRequestRelay.toString
    )
  }

  private def initializeKernelHandlers(
    actorSystem: ActorSystem, actorLoader: ActorLoader,
    commRegistrar: CommRegistrar, commStorage: CommStorage,
    responseMap: collection.mutable.Map[String, ActorRef]
  ): Unit = {
    def initializeRequestHandler[T](clazz: Class[T], messageType: MessageType) = {
      logger.debug("Creating %s handler".format(messageType.toString))
      actorSystem.actorOf(
        Props(clazz, actorLoader),
        name = messageType.toString
      )
    }

    def initializeInputHandler[T](
      clazz: Class[T],
      messageType: MessageType
    ): Unit = {
      logger.debug("Creating %s handler".format(messageType.toString))
      actorSystem.actorOf(
        Props(clazz, actorLoader, responseMap),
        name = messageType.toString
      )
    }

    // TODO: Figure out how to pass variable number of arguments to actor
    def initializeCommHandler[T](clazz: Class[T], messageType: MessageType) = {
      logger.debug("Creating %s handler".format(messageType.toString))
      actorSystem.actorOf(
        Props(clazz, actorLoader, commRegistrar, commStorage),
        name = messageType.toString
      )
    }

    def initializeSocketHandler(socketType: SocketType, messageType: MessageType): Unit = {
      logger.debug("Creating %s to %s socket handler ".format(messageType.toString ,socketType.toString))
      actorSystem.actorOf(
        Props(classOf[GenericSocketMessageHandler], actorLoader, socketType),
        name = messageType.toString
      )
    }

    //  These are the handlers for messages coming into the
    initializeRequestHandler(classOf[ExecuteRequestHandler],
      MessageType.Incoming.ExecuteRequest)
    initializeRequestHandler(classOf[KernelInfoRequestHandler],
      MessageType.Incoming.KernelInfoRequest)
    initializeRequestHandler(classOf[CodeCompleteHandler],
      MessageType.Incoming.CompleteRequest)
    initializeInputHandler(classOf[InputRequestReplyHandler],
      MessageType.Incoming.InputReply)
    initializeCommHandler(classOf[CommOpenHandler],
      MessageType.Incoming.CommOpen)
    initializeCommHandler(classOf[CommMsgHandler],
      MessageType.Incoming.CommMsg)
    initializeCommHandler(classOf[CommCloseHandler],
      MessageType.Incoming.CommClose)

    //  These are handlers for messages leaving the kernel through the sockets
    initializeSocketHandler(SocketType.Shell, MessageType.Outgoing.KernelInfoReply)
    initializeSocketHandler(SocketType.Shell, MessageType.Outgoing.ExecuteReply)
    initializeSocketHandler(SocketType.Shell, MessageType.Outgoing.CompleteReply)

    initializeSocketHandler(SocketType.StdIn, MessageType.Outgoing.InputRequest)

    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.ExecuteResult)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.Stream)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.ExecuteInput)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.Status)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.Error)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.CommOpen)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.CommMsg)
    initializeSocketHandler(SocketType.IOPub, MessageType.Outgoing.CommClose)
  }
}
