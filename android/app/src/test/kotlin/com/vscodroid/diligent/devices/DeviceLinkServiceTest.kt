package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.device.ServiceInfo
import com.ditarector.diligent.sync.port.Clock
import com.ditarector.diligent.sync.port.Connection
import com.ditarector.diligent.sync.port.ConnectionAcceptor
import com.ditarector.diligent.sync.port.ConnectionHandler
import com.ditarector.diligent.sync.port.Discovery
import com.ditarector.diligent.sync.port.DiscoveryListener
import com.ditarector.diligent.sync.port.Endpoint
import com.ditarector.diligent.sync.port.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.PriorityQueue

/** One queue for every device, with virtual time: nothing runs until the test drains it. */
private class FakeLoop : LinkLoop, Clock {
    private class Timed(val at: Long, val seq: Long, val task: () -> Unit, var cancelled: Boolean = false)
    private val now0 = 1_800_000_000_000L
    var now = now0
    private var seq = 0L
    private val queue = PriorityQueue<Timed>(compareBy<Timed>({ it.at }, { it.seq }))

    override fun nowMs(): Long = now
    override fun post(task: () -> Unit) {
        queue += Timed(now, seq++, task)
    }
    override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val t = Timed(now + delayMs, seq++, task)
        queue += t
        return { t.cancelled = true }
    }

    /** Runs everything due now, including what that posts. */
    fun drain() {
        var guard = 0
        while (true) {
            val next = queue.peek() ?: return
            if (next.at > now) return
            queue.poll()
            if (!next.cancelled) next.task()
            check(++guard < 100_000) { "loop does not settle" }
        }
    }

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            drain()
            val next = queue.peek()
            if (next == null || next.at > until) break
            now = next.at
        }
        now = until
        drain()
    }
}

/** An in-memory network of [FakeTransport]s; certificates and pins behave like TLS. */
private class FakeNetwork {
    val listeners = HashMap<Endpoint, Pair<() -> ByteArray, ConnectionAcceptor>>()
}

private class FakeConnection(override val peerCertificateDer: ByteArray, override val remoteHost: String) : Connection {
    var handler: ConnectionHandler? = null
    lateinit var other: FakeConnection
    var closed = false
    override fun send(bytes: ByteArray) {
        if (closed) throw IOException("closed")
        other.handler?.onBytes(bytes.copyOf())
    }
    override fun close() {
        if (closed) return
        closed = true
        handler?.onClosed(null)
        other.close()
    }
}

private class FakeTransport(private val network: FakeNetwork, val endpoint: Endpoint, var cert: () -> ByteArray) : Transport {
    override fun connect(endpoint: Endpoint, pinned: Fingerprint?, handler: ConnectionHandler): Connection {
        val (peerCert, acceptor) = network.listeners[endpoint] ?: throw IOException("connection refused")
        val presented = Fingerprint.ofCertificate(peerCert(), JvmHashing)
        if (pinned != null && presented != pinned) throw IOException("handshake", PinMismatchException(presented))
        val mine = FakeConnection(peerCert(), endpoint.host)
        val theirs = FakeConnection(cert(), this.endpoint.host)
        mine.other = theirs
        theirs.other = mine
        mine.handler = handler
        theirs.handler = acceptor.accept(theirs)
        return mine
    }
    override fun listen(port: Int, acceptor: ConnectionAcceptor): Int {
        network.listeners[endpoint] = cert to acceptor
        return endpoint.port
    }
    override fun stopListening() {
        network.listeners.remove(endpoint)
    }
}

private class FakeDiscovery : Discovery {
    var advertised: ServiceInfo? = null
    override fun advertise(info: ServiceInfo) {
        advertised = info
    }
    override fun stopAdvertising() {
        advertised = null
    }
    override fun browse(listener: DiscoveryListener) = Unit
    override fun stopBrowsing() = Unit
}

private class FakeHost(override val deviceName: String, override val projectsDir: File) : LinkHost {
    var saves = 0
    val snapshots = ArrayList<String>()
    override fun saveAll() {
        saves++
    }
    override fun snapshot(projectDir: File, label: String): String {
        snapshots += projectDir.name
        return "snap${snapshots.size}"
    }
    override fun ownTailscaleAddress(): String? = null
    override fun ownLanAddress(): String = "192.168.1.9"
    override fun text(problem: LinkProblem, name: String) = "$problem:$name"
    override fun reachableInBackground(on: Boolean) = Unit
}

class DeviceLinkServiceTest {
    private val loop = FakeLoop()
    private val network = FakeNetwork()

    private inner class Device(val name: String, val host: String, root: File) {
        var identity = DeviceIdentity.create()
        val endpoint = Endpoint(host, 47321)
        val transport = FakeTransport(network, endpoint) { identity.certificateDer() }
        val discovery = FakeDiscovery()
        val projects = File(root, "projects").apply { mkdirs() }
        val fakeHost = FakeHost(name, projects)
        val service = DeviceLinkService(
            identity = identity,
            transport = transport,
            discovery = discovery,
            srp = BcSrp(),
            host = fakeHost,
            storage = LinkStorage(File(root, "data")),
            loop = loop,
            io = { loop.post(it::run) },
            clock = loop,
        )
        val state get() = service.state.value
        val id get() = identity.deviceId.value
    }

    private fun devices(dir: File): Pair<Device, Device> {
        val a = Device("Phone A", "10.0.0.1", File(dir, "a"))
        val b = Device("Laptop B", "10.0.0.2", File(dir, "b"))
        for (d in listOf(a, b)) {
            d.service.setAppOpen(true)
        }
        loop.drain()
        return a to b
    }

    private fun pair(a: Device, b: Device) {
        b.service.setVisibility(Visibility.On)
        a.service.connectAddress(b.host, 47321, b.id)
        loop.drain()
        val code = (b.state.pairing as PairingUi.ShowCode).code
        a.service.submitCode(code)
        loop.drain()
    }

    @Test
    fun `defaults, listening and visibility while the screen is open`(@TempDir dir: File) {
        val (a, _) = devices(dir)
        assertEquals(Visibility.WhileScreenOpen, a.state.visibility)
        assertEquals(false, a.discovery.advertised?.acceptsPairing)
        a.service.screenOpen(true)
        loop.drain()
        assertEquals(true, a.discovery.advertised?.acceptsPairing)
        assertNotNull(a.service.pairingQr())
        a.service.setAppOpen(false)
        loop.drain()
        assertEquals(null, a.discovery.advertised)
        assertEquals(null, a.service.pairingQr())
    }

    @Test
    fun `pairing with a wrong code then the right one pairs both devices`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        b.service.setVisibility(Visibility.On)
        a.service.connectAddress(b.host, 47321, b.id)
        loop.drain()
        val show = b.state.pairing as PairingUi.ShowCode
        assertEquals("Phone A", show.requesterName)
        assertEquals(PairingUi.EnterCode(b.host, 5, wrongBefore = false), a.state.pairing)
        val wrong = if (show.code.startsWith("0")) "111 111" else "000 000"
        a.service.submitCode(wrong)
        loop.drain()
        assertEquals(PairingUi.EnterCode(b.host, 4, wrongBefore = true), a.state.pairing)
        a.service.submitCode(show.code)
        loop.drain()
        assertEquals(PairingUi.Paired("Laptop B"), a.state.pairing)
        assertEquals(PairingUi.Paired("Phone A"), b.state.pairing)
        assertEquals(listOf(b.id), a.state.myDevices.map { it.deviceId })
        assertEquals(listOf(a.id), b.state.myDevices.map { it.deviceId })
        assertEquals(LinkRoute.Wifi, a.state.myDevices.single().route)
    }

    @Test
    fun `five wrong codes lock pairing`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        b.service.setVisibility(Visibility.On)
        a.service.connectAddress(b.host, 47321, b.id)
        loop.drain()
        val code = (b.state.pairing as PairingUi.ShowCode).code
        val wrong = if (code.startsWith("0")) "111111" else "000000"
        repeat(5) {
            a.service.submitCode(wrong)
            loop.drain()
        }
        assertEquals(PairingUi.Locked(b.host), a.state.pairing)
        assertTrue(b.state.pairing is PairingUi.Locked)
        assertTrue(a.state.myDevices.isEmpty())
    }

    @Test
    fun `an expired code is refused`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        b.service.setVisibility(Visibility.On)
        a.service.connectAddress(b.host, 47321, b.id)
        loop.drain()
        loop.advance(121_000)
        assertEquals(PairingUi.Expired("Phone A"), b.state.pairing)
        assertEquals(PairingUi.Expired(b.host), a.state.pairing)
    }

    @Test
    fun `a device that is not visible refuses pairing requests`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        a.service.connectAddress(b.host, 47321, b.id)
        loop.drain()
        assertEquals(PairingUi.Failed(b.host, "NotAccepting:${b.host}"), a.state.pairing)
    }

    @Test
    fun `sync sends a new project, then a conflict is resolved, ignored folders never move`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        pair(a, b)
        val project = File(a.projects, "app").apply { mkdirs() }
        File(project, "index.js").writeText("one\n")
        File(project, "package.json").writeText("{}\n")
        File(project, "node_modules/x").mkdirs()
        File(project, "node_modules/x/i.js").writeText("dep")
        File(project, ".env").writeText("SECRET=1")

        a.service.planSync(b.id, project.path)
        loop.drain()
        val incoming = b.state.incomingProject!!
        assertTrue(incoming.isNew)
        assertEquals("app", incoming.name)
        b.service.answerIncomingProject(true)
        loop.drain()
        val plan = a.state.sync as SyncUi.Plan
        assertEquals(listOf(".diligent/project.json", "index.js", "package.json"), plan.newerHere.sorted())
        a.service.confirmSync()
        loop.drain()
        assertEquals(SyncUi.Done("Laptop B", 3, 0, null), a.state.sync)
        val copy = File(incoming.targetPath)
        assertEquals("one\n", File(copy, "index.js").readText())
        assertFalse(File(copy, "node_modules").exists())
        assertFalse(File(copy, ".env").exists())
        assertEquals(SyncUi.Done("Phone A", 0, 3, "npm install"), b.state.sync)
        assertTrue(a.fakeHost.saves > 0)
        assertEquals(listOf("app"), b.fakeHost.snapshots)

        // Both change the same file: a conflict, resolved with the other side's version.
        File(project, "index.js").writeText("mine\n")
        File(copy, "index.js").writeText("theirs\n")
        b.service.setApproval(a.id, Approval.AlwaysAllow)
        a.service.dismiss()
        loop.drain()
        a.service.planSync(b.id, project.path)
        loop.advance(DeviceLinkService.SAVE_SETTLE_MS)
        val second = a.state.sync as SyncUi.Plan
        assertEquals(listOf("index.js"), second.changedOnBoth)
        a.service.confirmSync()
        loop.drain()
        val conflicts = a.state.sync as SyncUi.Conflicts
        assertEquals(SyncConflict("index.js", "mine\n", "theirs\n"), conflicts.conflicts.single())
        assertTrue(File(project, "index (from Laptop B).js").exists())
        a.service.resolveConflict("index.js", ConflictChoice.KeepTheirs)
        loop.drain()
        assertTrue(a.state.sync is SyncUi.Done)
        assertEquals("theirs\n", File(project, "index.js").readText())
        assertFalse(File(project, "index (from Laptop B).js").exists())
        assertEquals(listOf("app", "app"), a.fakeHost.snapshots.takeLast(2).let { listOf(it.first(), it.last()) })
        assertNotNull(a.state.myDevices.single().lastSyncMs)
    }

    @Test
    fun `a responder that asks first shows the request and can decline`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        pair(a, b)
        val project = File(a.projects, "site").apply { mkdirs() }
        File(project, "a.txt").writeText("a")
        val existing = File(b.projects, "site").apply { mkdirs() }
        a.service.planSync(b.id, project.path)
        loop.drain()
        b.service.answerIncomingProject(false)
        loop.drain()
        assertEquals(SyncUi.Failed("Laptop B", "Declined:Laptop B"), a.state.sync)
        assertTrue(existing.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a paired device with a new certificate is blocked as an identity change`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        pair(a, b)
        val bId = b.id
        b.identity = DeviceIdentity.create()
        val project = File(a.projects, "p").apply { mkdirs() }
        a.service.planSync(bId, project.path)
        loop.drain()
        assertEquals(SyncUi.Failed("Laptop B", "IdentityChanged:Laptop B"), a.state.sync)
        val device = a.state.myDevices.single()
        assertTrue(device.identityChanged)
        assertEquals(null, device.route)
        a.service.dismiss()
        loop.drain()
        a.service.planSync(bId, project.path)
        loop.drain()
        assertEquals(SyncUi.Failed("Laptop B", "IdentityChanged:Laptop B"), a.state.sync)
    }

    @Test
    fun `phone to phone mode explains it is not available`(@TempDir dir: File) {
        val (a, b) = devices(dir)
        pair(a, b)
        a.service.setConnectionMode(ConnectionMode.PhoneToPhone)
        a.service.planSync(b.id, File(a.projects, "p").apply { mkdirs() }.path)
        loop.drain()
        assertEquals(SyncUi.Failed("Laptop B", "PhoneToPhoneUnavailable:Laptop B"), a.state.sync)
    }

    @Test
    fun `install hints prefer the lock file`() {
        assertEquals("pnpm install", InstallHints.forPaths(listOf("web/package.json", "web/pnpm-lock.yaml")))
        assertEquals("npm install", InstallHints.forPaths(listOf("package.json")))
        assertEquals(null, InstallHints.forPaths(listOf("src/main.kt")))
    }
}
