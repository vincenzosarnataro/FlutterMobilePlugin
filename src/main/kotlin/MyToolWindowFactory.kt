package it.sarni

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import javax.swing.JButton
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class MyToolWindow(private val project: Project) {
        private val deviceComboBox = ComboBox<AdbDevice>().apply {
            toolTipText = MyMessageBundle.message("toolwindow.MyToolWindow.devices.tooltip")
            // Refresh the device list every time the dropdown is opened.
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = refreshDevices()
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = Unit
                override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
            })
        }

        private val content = JBPanel<JBPanel<*>>().apply {
            add(deviceComboBox)
            add(JButton(MyMessageBundle.message("toolwindow.MyToolWindow.flutterRun.button")).apply {
                addActionListener { runFlutterWeb() }
            })
        }

        init {
            refreshDevices()
        }

        private fun runFlutterWeb() {
            try {
                val ip = localIpAddress()
                val device = deviceComboBox.selectedItem as? AdbDevice
                val manager = TerminalToolWindowManager.getInstance(project)

                // Tab 1: long-running Flutter web server.
                manager.createShellWidget(project.basePath, "Flutter Web", true, false)
                    .sendCommandToExecute(flutterRunCommand(ip))

                // Tab 2: open Chrome on the selected Android device pointing at the server.
                manager.createShellWidget(project.basePath, "Open Chrome", false, false)
                    .sendCommandToExecute(openChromeCommand(ip, device?.serial))
            } catch (e: Exception) {
                LOG.warn("Unable to start the Flutter web server", e)
                Messages.showErrorDialog(
                    project,
                    MyMessageBundle.message("toolwindow.MyToolWindow.flutterRun.error", e.message ?: ""),
                    MyMessageBundle.message("toolwindow.MyToolWindow.flutterRun.button")
                )
            }
        }

        fun getContent(): JBPanel<JBPanel<*>> = content

        private fun flutterRunCommand(ip: String): String =
            "flutter run -d web-server --web-port 8080 --web-hostname $ip"

        private fun openChromeCommand(ip: String, serial: String?): String {
            val url = "http://$ip:8080"
            val adbTarget = if (serial != null) "adb -s $serial" else "adb"
            val adb = "$adbTarget shell am start -a android.intent.action.VIEW -d \"$url\""
            // Poll the web server until it answers, then open Chrome on the device.
            return "echo 'Waiting for the Flutter web server at $url ...'; " +
                "until curl -s -o /dev/null \"$url\"; do sleep 10; done; " +
                "echo 'Server ready, opening Chrome on the device'; $adb"
        }

        /** Re-reads the connected Android devices and repopulates the dropdown, keeping the current selection. */
        private fun refreshDevices() {
            val previousSerial = (deviceComboBox.selectedItem as? AdbDevice)?.serial
            val devices = listAdbDevices()
            deviceComboBox.removeAllItems()
            devices.forEach { deviceComboBox.addItem(it) }
            deviceComboBox.selectedItem =
                devices.firstOrNull { it.serial == previousSerial } ?: devices.firstOrNull()
        }

        /** Runs `adb devices -l` and returns the devices that are online ("device" state). */
        private fun listAdbDevices(): List<AdbDevice> =
            try {
                val process = ProcessBuilder(adbExecutable(), "devices", "-l")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.lineSequence()
                    .drop(1) // skip the "List of devices attached" header
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val tokens = line.split(Regex("\\s+"))
                        val serial = tokens.getOrNull(0) ?: return@mapNotNull null
                        if (tokens.getOrNull(1) != "device") return@mapNotNull null // skip offline/unauthorized
                        val model = tokens.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                        AdbDevice(serial, if (model != null) "$model ($serial)" else serial)
                    }
                    .toList()
            } catch (e: Exception) {
                LOG.warn("Unable to list adb devices", e)
                emptyList()
            }

        /** Resolves the adb binary, since the IDE process often lacks the Android SDK on its PATH. */
        private fun adbExecutable(): String {
            val candidates = buildList {
                System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
                System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
                add("${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb")
            }
            return candidates.firstOrNull { File(it).canExecute() } ?: "adb"
        }

        /** Returns the machine's primary LAN IP, falling back to loopback if it can't be determined. */
        private fun localIpAddress(): String =
            try {
                DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
                    socket.localAddress.hostAddress
                }
            } catch (e: Exception) {
                LOG.warn("Unable to determine the local IP address, falling back to 127.0.0.1", e)
                "127.0.0.1"
            }

        /** A connected Android device; [toString] is what the dropdown shows. */
        private data class AdbDevice(val serial: String, val label: String) {
            override fun toString(): String = label
        }

        companion object {
            private val LOG = logger<MyToolWindow>()
        }
    }
}
