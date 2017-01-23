package org.nlogo.ls.gui

import org.nlogo.app.tools.AgentMonitorManager
import org.nlogo.lite.ProceduresLite
import org.nlogo.window.{Event, NetLogoListenerManager, CompilerManager,
  DefaultEditorFactory, LinkRoot, InterfacePanelLite, UpdateManager,
  GUIWorkspace, FileController, OutputWidget, ReconfigureWorkspaceUI}
import org.nlogo.window.Events.{CompiledEvent, LoadModelEvent}
import org.nlogo.api
import org.nlogo.api.{ ControlSet, Version, NetLogoThreeDDialect, NetLogoLegacyDialect, AggregateManagerInterface, RendererInterface, ModelType }
import org.nlogo.agent.{World, World3D, Agent}
import org.nlogo.core.{AgentKind, Model}
import org.nlogo.nvm
import org.nlogo.nvm.{CompilerInterface}
import org.nlogo.awt.EventQueue
import org.nlogo.workspace.{ OpenModel, OpenModelFromURI }
import org.nlogo.fileformat

import java.awt.EventQueue.isDispatchThread
import java.awt.image.BufferedImage
import java.nio.file.Paths

import scala.util.Try
import scala.concurrent.{ Future, Promise }

abstract class InterfaceComponent(frame: javax.swing.JFrame) extends javax.swing.JPanel
with Event.LinkParent
with LinkRoot
with ControlSet {
  val listenerManager = new NetLogoListenerManager
  val world = if(Version.is3D) new World3D() else new World

  // KioskLevel.NONE - We want a 3d button
  val workspace: GUIWorkspace = new GUIWorkspace(world, GUIWorkspace.KioskLevel.NONE, frame, frame, null, null, listenerManager, this) {
    val compiler = new org.nlogo.compile.Compiler(if (Version.is3D) NetLogoThreeDDialect else NetLogoLegacyDialect)

    lazy val updateManager = new UpdateManager {
      override def defaultFrameRate = workspace.frameRate
      override def ticks = workspace.world.tickCounter.ticks
      override def updateMode = workspace.updateMode
    }

    val aggregateManager = new org.nlogo.sdm.AggregateManagerLite


    override def inspectAgent(agent: api.Agent, radius: Double) = {
      val a = agent.asInstanceOf[Agent]
      monitorManager.inspect(a.kind, a, radius)
    }
    override def inspectAgent(kind: AgentKind, agent: Agent, radius: Double) =
      monitorManager.inspect(kind, agent, radius)
    override def stopInspectingAgent(agent: Agent): Unit = monitorManager.stopInspecting(agent)
    override def stopInspectingDeadAgents(): Unit = monitorManager.stopInspectingDeadAgents()
    override def closeAgentMonitors() = monitorManager.closeAll()
    override def newRenderer = new org.nlogo.render.Renderer(world)
    override def updateModel(m: Model): Model = m
  }

  val monitorManager = new AgentMonitorManager(workspace)
  addLinkComponent(monitorManager)

  val viewManager = new org.nlogo.gl.view.ViewManager(workspace, frame, new java.awt.event.KeyAdapter{})
  workspace.init(viewManager)
  addLinkComponent(viewManager)

  val procedures = new ProceduresLite(workspace, workspace)
  val liteEditorFactory = new DefaultEditorFactory(workspace)
  val interfacePanel = createInterfacePanel(workspace)

  addLinkComponent(workspace.aggregateManager)
  addLinkComponent(workspace)
  addLinkComponent(procedures)
  addLinkComponent(new CompilerManager(workspace, procedures))
  addLinkComponent(new CompiledEvent.Handler {
    override def handle(e: CompiledEvent) {
      if (e.error != null)
        throw e.error
  }})
  addLinkComponent(new LoadModelEvent.Handler {
    override def handle(e: LoadModelEvent) {
      workspace.aggregateManager.load(e.model, workspace)
  }})
  addLinkComponent(listenerManager)

  workspace.setWidgetContainer(interfacePanel)
  setBackground(java.awt.Color.WHITE)
  add(interfacePanel)

  def open(path: String) = {
    EventQueue.mustBeEventDispatchThread()
    val uri = Paths.get(path).toUri
    interfacePanel.reset()
    val controller = new FileController(this, workspace)
    val loader = fileformat.basicLoader
    val modelOpt = OpenModelFromURI(uri, controller, loader, fileformat.defaultConverter, Version)
    modelOpt.foreach(model => ReconfigureWorkspaceUI(this, uri, ModelType.Library, model, workspace))
  }

  protected def createInterfacePanel(workspace: GUIWorkspace): InterfacePanelLite

  def userInterface: Future[BufferedImage] = {
    import org.nlogo.swing.Implicits.thunk2runnable
    if (isDispatchThread)
      Promise.fromTry(Try(interfacePanel.interfaceImage)).future
    else {
      val promise = Promise[BufferedImage]()
      EventQueue.invokeLater { () =>
        promise.complete(Try(interfacePanel.interfaceImage))
        ()
      }
      promise.future
    }
  }

  def userOutput: Future[String] = {
    import org.nlogo.swing.Implicits.thunk2runnable
    def findOutput(ipl: InterfacePanelLite): String =
      ipl.getComponents.collect {
        case ow: OutputWidget => ow.valueText
      }.headOption.getOrElse("")
    if (isDispatchThread)
      Promise.fromTry(Try(findOutput(interfacePanel))).future
    else {
      val promise = Promise[String]()
      EventQueue.invokeLater { () =>
        promise.complete(Try(findOutput(interfacePanel)))
        ()
      }
      promise.future
    }
  }
}
