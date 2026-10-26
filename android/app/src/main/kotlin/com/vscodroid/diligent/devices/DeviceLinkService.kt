package com.vscodroid.diligent.devices

import com.ditarector.diligent.sync.DiligentLink
import com.ditarector.diligent.sync.device.Approval as WireApproval
import com.ditarector.diligent.sync.device.DeviceId
import com.ditarector.diligent.sync.device.DeviceRegistry
import com.ditarector.diligent.sync.device.Fingerprint
import com.ditarector.diligent.sync.device.LastAddresses
import com.ditarector.diligent.sync.device.LocalDevice
import com.ditarector.diligent.sync.device.ManualAddress
import com.ditarector.diligent.sync.device.PairingRecord
import com.ditarector.diligent.sync.device.PeerCheck
import com.ditarector.diligent.sync.device.Platform
import com.ditarector.diligent.sync.device.ServiceInfo
import com.ditarector.diligent.sync.manifest.ConflictKind
import com.ditarector.diligent.sync.manifest.IgnoreRules
import com.ditarector.diligent.sync.manifest.Manifest
import com.ditarector.diligent.sync.manifest.ManifestBuilder
import com.ditarector.diligent.sync.manifest.ProjectPaths
import com.ditarector.diligent.sync.manifest.SyncPlan
import com.ditarector.diligent.sync.message.ErrorCodes
import com.ditarector.diligent.sync.message.ErrorMessage
import com.ditarector.diligent.sync.message.PairForget
import com.ditarector.diligent.sync.message.SyncHello
import com.ditarector.diligent.sync.pairing.PairingRequester
import com.ditarector.diligent.sync.pairing.PairingTarget
import com.ditarector.diligent.sync.pairing.RequesterState
import com.ditarector.diligent.sync.pairing.TargetEvent
import com.ditarector.diligent.sync.pairing.HideReason
import com.ditarector.diligent.sync.port.Clock
import com.ditarector.diligent.sync.port.Connection
import com.ditarector.diligent.sync.port.ConnectionAcceptor
import com.ditarector.diligent.sync.port.ConnectionHandler
import com.ditarector.diligent.sync.port.DiscoveredPeer
import com.ditarector.diligent.sync.port.Discovery
import com.ditarector.diligent.sync.port.DiscoveryListener
import com.ditarector.diligent.sync.port.Endpoint
import com.ditarector.diligent.sync.port.Hashing
import com.ditarector.diligent.sync.port.PathFilter
import com.ditarector.diligent.sync.port.Identity
import com.ditarector.diligent.sync.port.SecureRandomSource
import com.ditarector.diligent.sync.port.Snapshotter
import com.ditarector.diligent.sync.port.Srp
import com.ditarector.diligent.sync.port.Transport
import com.ditarector.diligent.sync.session.SyncSession
import com.ditarector.diligent.sync.session.SyncState
import com.ditarector.diligent.sync.wire.InboundReader
import com.ditarector.diligent.sync.wire.Inbound
import com.ditarector.diligent.sync.wire.Outbox
import com.vscodroid.diligent.TailscaleAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executor

/**
 * [DeviceLink] over sync-core and the Android adapters (Phase 5 Parts B, D, E).
 *
 * Everything runs on [loop], one thread, because sync-core's pairing and sync
 * state machines are not thread-safe; blocking connects run on [io] and post
 * their result back. The public methods only post.
 *
 * **Reachability (A.3).** It listens and advertises while the app is open
 * ([setAppOpen]), while a task runs ([setTaskRunning]) or, with "Stay
 * reachable in the background", whenever the process lives, which the editor's
 * foreground service keeps it doing; [LinkHost.reachableInBackground] puts a
 * line about it on that service's notification. It browses only while the
 * app is open.
 *
 * **Visibility.** The advertisement always carries `pair=1` or `pair=0`, so
 * paired devices still find this one when it is not visible; pairing
 * requests are accepted only while visible (On, or the Devices screen open
 * with the default "Only while this screen is open").
 *
 * **Connections (Part E).** Automatic tries the same network first (the
 * address found by DNS-SD, else the last one used), then Tailscale when this
 * phone has a Tailscale address and the peer's is known. The route used is
 * shown; when nothing works the reason is a plain sentence ([LinkProblem]).
 * Phone to phone is a later part and reports itself unavailable.
 *
 * SPEC v1 has no message that tells a peer its Tailscale address, so it is
 * learned from a connection that arrives over Tailscale, or from an address
 * the user types for a paired device ([connectAddress]).
 */
class DeviceLinkService(
    identity: Identity,
    private val transport: Transport,
    private val discovery: Discovery,
    private val srp: Srp,
    private val host: LinkHost,
    private val storage: LinkStorage,
    private val loop: LinkLoop,
    private val io: Executor,
    private val hashing: Hashing = JvmHashing,
    private val clock: Clock = SystemClockPort,
    private val random: SecureRandomSource = JvmSecureRandom(),
    /** Called with the bound port before advertising (the NSD adapter needs it). */
    private val onListening: (Int) -> Unit = {},
) : DeviceLink {
    private val local = LocalDevice.from(identity, hashing, host.deviceName, Platform.ANDROID)
    private val registry: DeviceRegistry = storage.loadRegistry()
    private var settings: LinkSettings = storage.loadSettings()
    private val projects: MutableMap<String, String> = storage.loadProjects()

    private val flow = MutableStateFlow(
        DevicesState(
            myName = local.name,
            myDeviceId = local.deviceId.display,
            visibility = settings.visibility,
            stayReachable = settings.stayReachable,
            connectionMode = settings.connectionMode,
        ),
    )
    override val state: StateFlow<DevicesState> = flow

    // Reachability
    private var appOpen = false
    private var taskRunning = false
    private var screenIsOpen = false
    private var listening = false
    private var browsing = false
    @Volatile private var boundPort: Int? = null
    private var advertised: ServiceInfo? = null

    // What the network shows
    private val found = LinkedHashMap<DeviceId, DiscoveredPeer>()
    private val routes = HashMap<DeviceId, LinkRoute>()
    private val identityChanged = HashSet<DeviceId>()

    // Pairing
    private val target = PairingTarget(local, srp, hashing, clock, random, PairingTarget.Listener(::onTargetEvent))
    private var attempt: Attempt? = null
    private var targetLink: Link? = null
    private var targetName = ""
    private var expiryTimer: (() -> Unit)? = null

    // Sync
    private var job: SyncJob? = null
    private var incoming: PendingIncoming? = null
    private var conflicts: ConflictSet? = null
    private val lastLocal = HashMap<String, Manifest>()

    // Automatic sync
    private val autoChanges = HashMap<AutoSyncKey, AutoWatch>()
    private var autoTimer: (() -> Unit)? = null

    init {
        loop.post {
            publish()
            applyReach()
            scheduleAuto()
        }
    }

    // ---- reachability ----

    /** The app's activity is started or stopped. */
    fun setAppOpen(open: Boolean) = loop.post {
        appOpen = open
        applyReach()
    }

    /** A supervised task is running or restarting. */
    fun setTaskRunning(running: Boolean) = loop.post {
        taskRunning = running
        applyReach()
    }

    override fun screenOpen(open: Boolean) = loop.post {
        screenIsOpen = open
        applyReach()
        if (open) probeTailscale()
    }

    override fun setVisibility(visibility: Visibility) = loop.post {
        changeSettings { it.copy(visibility = visibility) }
        applyReach()
    }

    override fun setStayReachable(on: Boolean) = loop.post {
        changeSettings { it.copy(stayReachable = on) }
        applyReach()
    }

    override fun setConnectionMode(mode: ConnectionMode) = loop.post {
        changeSettings { it.copy(connectionMode = mode) }
    }

    private val visible: Boolean
        get() = when (settings.visibility) {
            Visibility.On -> true
            Visibility.Off -> false
            Visibility.WhileScreenOpen -> screenIsOpen
        }

    private fun applyReach() {
        val shouldListen = appOpen || taskRunning || settings.stayReachable
        if (shouldListen && !listening) {
            try {
                val port = transport.listen(DiligentLink.DEFAULT_PORT, acceptor)
                boundPort = port
                listening = true
                onListening(port)
            } catch (e: IOException) {
                boundPort = null
            }
        } else if (!shouldListen && listening) {
            transport.stopListening()
            discovery.stopAdvertising()
            listening = false
            boundPort = null
            advertised = null
        }
        target.acceptingRequests = listening && visible
        if (listening) {
            val info = ServiceInfo(local.name, Platform.ANDROID, local.deviceId, acceptsPairing = visible)
            if (info != advertised) {
                discovery.advertise(info)
                advertised = info
            }
        }
        val shouldBrowse = appOpen
        if (shouldBrowse && !browsing) {
            discovery.browse(discoveryListener)
            browsing = true
        } else if (!shouldBrowse && browsing) {
            discovery.stopBrowsing()
            browsing = false
            found.clear()
            routes.entries.removeAll { it.value == LinkRoute.Wifi }
            publish()
        }
        host.reachableInBackground(listening && settings.stayReachable && !appOpen)
    }

    private val discoveryListener = object : DiscoveryListener {
        override fun onFound(peer: DiscoveredPeer) = loop.post {
            if (peer.info.deviceId == local.deviceId) return@post
            found[peer.info.deviceId] = peer
            registry[peer.info.deviceId]?.let {
                routes[it.deviceId] = LinkRoute.Wifi
                registry.update(it.deviceId) { r ->
                    r.copy(lastSeen = clock.nowMs(), lastAddresses = r.lastAddresses.copy(lan = peer.endpoint.toString()))
                }
                storage.saveRegistry(registry)
            }
            publish()
        }

        override fun onLost(deviceId: DeviceId) = loop.post {
            found.remove(deviceId)
            if (routes[deviceId] == LinkRoute.Wifi) routes.remove(deviceId)
            publish()
        }
    }

    override fun pairingQr(): String? {
        val port = boundPort ?: return null
        val address = host.ownLanAddress() ?: host.ownTailscaleAddress() ?: return null
        return ManualAddress(Endpoint(address, port), local.deviceId).toUri()
    }

    // ---- connections ----

    /** One TLS connection and what runs on it. Only touched on [loop]. */
    private inner class Link(val connection: Connection, val incoming: Boolean, val route: LinkRoute?) {
        val outbox = Outbox(QuietConnection(connection))
        val reader = InboundReader()
        val peer: Fingerprint = Fingerprint.ofCertificate(connection.peerCertificateDer, hashing)
        var closed = false

        fun close() {
            if (closed) return
            closed = true
            connection.close()
        }
    }

    /** Sends never throw: a failed write closes the connection, and its reader reports that. */
    private class QuietConnection(private val inner: Connection) : Connection by inner {
        override fun send(bytes: ByteArray) {
            try {
                inner.send(bytes)
            } catch (e: IOException) {
                inner.close()
            }
        }
    }

    /** Buffers what arrives before the [Link] exists, then feeds it on [loop] in order. */
    private inner class Pipe : ConnectionHandler {
        var link: Link? = null
        private val early = ArrayList<ByteArray>()
        private var closedEarly = false
        private var closeError: Throwable? = null

        override fun onBytes(bytes: ByteArray) = loop.post {
            val l = link
            if (l == null) early += bytes else feed(l, bytes)
        }

        override fun onClosed(error: Throwable?) = loop.post {
            val l = link
            if (l == null) {
                closedEarly = true
                closeError = error
            } else {
                onLinkClosed(l, error)
            }
        }

        fun attach(l: Link) {
            link = l
            early.forEach { feed(l, it) }
            early.clear()
            if (closedEarly) onLinkClosed(l, closeError)
        }
    }

    private val acceptor = ConnectionAcceptor { connection ->
        val pipe = Pipe()
        loop.post { onIncoming(pipe, connection) }
        pipe
    }

    private fun onIncoming(pipe: Pipe, connection: Connection) {
        val route = if (isTailscale(connection.remoteHost)) LinkRoute.Tailscale else LinkRoute.Wifi
        val link = Link(connection, incoming = true, route = route)
        val check = registry.check(link.peer)
        if (check is PeerCheck.Paired) {
            routes[check.record.deviceId] = route
            registry.update(check.record.deviceId) { r ->
                val seen = Endpoint(connection.remoteHost.ifEmpty { "0.0.0.0" }, DiligentLink.DEFAULT_PORT).toString()
                r.copy(
                    lastSeen = clock.nowMs(),
                    lastAddresses = if (route == LinkRoute.Tailscale) r.lastAddresses.copy(tailscale = seen) else r.lastAddresses,
                )
            }
            storage.saveRegistry(registry)
            publish()
        }
        pipe.attach(link)
    }

    private fun feed(link: Link, bytes: ByteArray) {
        if (link.closed) return
        val items = try {
            link.reader.feed(bytes)
        } catch (e: RuntimeException) {
            // A frame this side refuses to read (too large, not JSON): the connection is unusable.
            link.close()
            return
        }
        for (item in items) {
            try {
                dispatch(link, item)
            } catch (e: IOException) {
                failActive(link, LinkProblem.SyncFailed)
            } catch (e: RuntimeException) {
                failActive(link, LinkProblem.SyncFailed)
            }
        }
    }

    private fun dispatch(link: Link, item: Inbound) {
        when (item) {
            is Inbound.Unsupported -> {
                link.outbox.replyUnsupported(item)
                return
            }
            is Inbound.Invalid -> {
                link.outbox.replyError(item, ErrorCodes.INVALID, item.reason)
                return
            }
            else -> Unit
        }
        attempt?.takeIf { it.link === link }?.let { if (it.requester.handle(item)) return }
        job?.takeIf { it.link === link }?.let { if (it.session?.handle(item) == true) return }
        val message = (item as? Inbound.Received)?.message ?: return
        if (link.incoming && message.allowedBeforePairing) {
            targetLink = link
            if (target.handle(link.outbox, item)) return
        }
        when (val check = registry.check(link.peer)) {
            is PeerCheck.Paired -> when (message) {
                is SyncHello -> onHello(link, item, message, check.record)
                is PairForget -> {
                    forgetLocally(check.record.deviceId)
                    link.close()
                }
                is ErrorMessage -> Unit
                else -> link.outbox.replyError(item, ErrorCodes.UNEXPECTED)
            }
            // Before pairing only pairing messages are allowed (A.4).
            else -> if (message !is ErrorMessage) link.outbox.replyError(item, ErrorCodes.UNEXPECTED, "pair first")
        }
    }

    private fun onLinkClosed(link: Link, error: Throwable?) {
        link.closed = true
        attempt?.takeIf { it.link === link && !it.requester.state.isFinal }?.let {
            attempt = null
            setPairing(PairingUi.Failed(it.name, host.text(LinkProblem.ConnectionLost, it.name)))
        }
        target.onDisconnected(link.outbox)
        job?.takeIf { it.link === link }?.let { current ->
            val session = current.session
            if (session == null) {
                job = null
            } else if (!session.state.isFinal) {
                session.cancel()
                job = null
                setSync(SyncUi.Failed(current.deviceName, host.text(LinkProblem.ConnectionLost, current.deviceName)))
            }
        }
        if (incoming?.link === link) {
            incoming = null
            flow.value = flow.value.copy(incomingProject = null)
        }
    }

    private fun failActive(link: Link, problem: LinkProblem) {
        job?.takeIf { it.link === link }?.let {
            it.session?.cancel()
            job = null
            setSync(SyncUi.Failed(it.deviceName, host.text(problem, it.deviceName)))
        }
        link.close()
    }

    /**
     * Where [record] may be reached in the current [ConnectionMode], best
     * first, or the reason there is nowhere to try.
     */
    private fun candidates(record: PairingRecord?, typed: Endpoint? = null): Pair<List<Pair<Endpoint, LinkRoute>>, LinkProblem?> {
        val id = record?.deviceId
        val lan = typed?.takeIf { !isTailscale(it.host) }
            ?: id?.let { found[it]?.endpoint }
            ?: record?.lastAddresses?.lan?.let { ManualAddress.parseEndpoint(it) }
        val tailscale = typed?.takeIf { isTailscale(it.host) }
            ?: record?.lastAddresses?.tailscale?.let { ManualAddress.parseEndpoint(it) }
        val ownTailscale = host.ownTailscaleAddress()
        val onNetwork = host.ownLanAddress() != null
        val list = ArrayList<Pair<Endpoint, LinkRoute>>()
        val mode = settings.connectionMode
        if (mode == ConnectionMode.PhoneToPhone) return list to LinkProblem.PhoneToPhoneUnavailable
        if (mode != ConnectionMode.TailscaleOnly && lan != null) list += lan to LinkRoute.Wifi
        if (mode != ConnectionMode.WifiOrHotspot && tailscale != null && ownTailscale != null) list += tailscale to LinkRoute.Tailscale
        if (list.isNotEmpty()) return list to null
        val problem = when (mode) {
            ConnectionMode.TailscaleOnly -> if (ownTailscale == null) LinkProblem.NoTailscaleHere else LinkProblem.NoTailscaleAddress
            ConnectionMode.WifiOrHotspot -> if (onNetwork) LinkProblem.NotFound else LinkProblem.NotOnNetwork
            else -> if (!onNetwork && ownTailscale == null) LinkProblem.NotOnNetwork else LinkProblem.NotFound
        }
        return list to problem
    }

    /**
     * Connects to the first of [tries] that answers, off [loop], then calls
     * [done] on [loop] with the link, or with the reason none worked.
     */
    private fun connectFirst(
        tries: List<Pair<Endpoint, LinkRoute>>,
        pinned: Fingerprint?,
        done: (Link?, LinkProblem?, Endpoint?) -> Unit,
    ) {
        io.execute {
            var problem = LinkProblem.Unreachable
            for ((endpoint, route) in tries) {
                val pipe = Pipe()
                try {
                    val connection = transport.connect(endpoint, pinned, pipe)
                    loop.post {
                        val link = Link(connection, incoming = false, route = route)
                        pipe.attach(link)
                        done(link, null, endpoint)
                    }
                    return@execute
                } catch (e: Exception) {
                    if (isPinMismatch(e)) {
                        loop.post { done(null, LinkProblem.IdentityChanged, endpoint) }
                        return@execute
                    }
                    // A same-network address that does not answer is the "different networks" case.
                    if (route == LinkRoute.Wifi) problem = LinkProblem.NotFound
                }
            }
            loop.post { done(null, problem, null) }
        }
    }

    private fun probeTailscale() {
        if (host.ownTailscaleAddress() == null || settings.connectionMode == ConnectionMode.WifiOrHotspot) return
        for (record in registry.records) {
            if (routes.containsKey(record.deviceId) || record.deviceId in identityChanged) continue
            val endpoint = record.lastAddresses.tailscale?.let { ManualAddress.parseEndpoint(it) } ?: continue
            val pin = record.pinnedFingerprint ?: continue
            connectFirst(listOf(endpoint to LinkRoute.Tailscale), pin) { link, problem, _ ->
                if (link != null) {
                    routes[record.deviceId] = LinkRoute.Tailscale
                    link.close()
                } else if (problem == LinkProblem.IdentityChanged) {
                    identityChanged += record.deviceId
                }
                publish()
            }
        }
    }

    // ---- pairing: requester ----

    private class Attempt(val link: Link, val requester: PairingRequester, val name: String, val endpoint: Endpoint, val route: LinkRoute) {
        var cancelledHere = false
    }

    override fun connect(nearbyDeviceId: String) = loop.post {
        val id = DeviceId.parse(nearbyDeviceId)
        val peer = id?.let { found[it] }
        if (peer == null) {
            setPairing(PairingUi.Failed(nearbyDeviceId, host.text(LinkProblem.NotFound, nearbyDeviceId)))
            return@post
        }
        startPairing(peer.info.name, listOf(peer.endpoint to LinkRoute.Wifi), peer.info.deviceId)
    }

    override fun connectAddress(host: String, port: Int, deviceId: String?) = loop.post {
        val id = deviceId?.let { DeviceId.parse(it) }
        val endpoint = try {
            Endpoint(host.trim(), port)
        } catch (e: IllegalArgumentException) {
            setPairing(PairingUi.Failed(host, this.host.text(LinkProblem.NotFound, host)))
            return@post
        }
        val known = id?.let { registry[it] }
        if (known != null && known.deviceId !in identityChanged) {
            // A paired device at a typed address: remember it (this is how a Tailscale address is learned) and check it answers.
            rememberAddress(known.deviceId, endpoint)
            val pin = known.pinnedFingerprint ?: return@post
            connectFirst(listOf(endpoint to routeOf(endpoint)), pin) { link, problem, _ ->
                if (link != null) {
                    routes[known.deviceId] = routeOf(endpoint)
                    link.close()
                    setPairing(PairingUi.Paired(known.name))
                } else {
                    if (problem == LinkProblem.IdentityChanged) identityChanged += known.deviceId
                    setPairing(failure(known.name, problem ?: LinkProblem.Unreachable))
                }
                publish()
            }
            return@post
        }
        startPairing(known?.name ?: endpoint.host, listOf(endpoint to routeOf(endpoint)), id)
    }

    private fun startPairing(name: String, tries: List<Pair<Endpoint, LinkRoute>>, expected: DeviceId?) {
        attempt?.let {
            it.cancelledHere = true
            it.requester.cancel()
            it.link.close()
        }
        attempt = null
        if (settings.connectionMode == ConnectionMode.PhoneToPhone) {
            setPairing(failure(name, LinkProblem.PhoneToPhoneUnavailable))
            return
        }
        setPairing(PairingUi.Requesting(name))
        connectFirst(tries, pinned = null) { link, problem, endpoint ->
            if (link == null || endpoint == null) {
                setPairing(failure(name, problem ?: LinkProblem.Unreachable))
                return@connectFirst
            }
            val check = registry.check(link.peer, expected)
            if (check is PeerCheck.IdentityChanged) {
                // A known device answering with another certificate: blocked until the user pairs the new one (A.4).
                identityChanged += check.record.deviceId
                link.close()
                setPairing(PairingUi.IdentityChanged(check.record.name))
                publish()
                return@connectFirst
            }
            val requester = PairingRequester(link.outbox, local, srp, hashing, clock, PairingRequester.Listener { onRequesterState(it) }, expected)
            val current = Attempt(link, requester, name, endpoint, link.route ?: LinkRoute.Wifi)
            attempt = current
            requester.start()
        }
    }

    private fun onRequesterState(state: RequesterState) {
        val current = attempt ?: return
        val name = current.name
        when (state) {
            RequesterState.Idle, RequesterState.Proving, RequesterState.Binding, is RequesterState.WrongCode -> Unit
            RequesterState.WaitingForChallenge -> if (flow.value.pairing !is PairingUi.EnterCode) setPairing(PairingUi.Requesting(name))
            is RequesterState.NeedCode ->
                setPairing(PairingUi.EnterCode(name, state.remaining ?: PairingTarget.MAX_FAILURES, wrongBefore = state.remaining != null))
            is RequesterState.Paired -> {
                val endpoint = current.endpoint.toString()
                val addresses = if (current.route == LinkRoute.Tailscale) LastAddresses(tailscale = endpoint) else LastAddresses(lan = endpoint)
                routes[state.record.deviceId] = current.route
                addPaired(state.record.copy(lastSeen = clock.nowMs(), lastAddresses = addresses))
                endAttempt()
                setPairing(PairingUi.Paired(state.record.name))
            }
            RequesterState.Expired -> {
                endAttempt()
                setPairing(PairingUi.Expired(name))
            }
            is RequesterState.Locked -> {
                endAttempt()
                setPairing(PairingUi.Locked(name))
            }
            RequesterState.Cancelled -> {
                val here = current.cancelledHere
                endAttempt()
                if (!here) setPairing(failure(name, LinkProblem.Refused))
            }
            is RequesterState.Failed -> {
                endAttempt()
                setPairing(
                    failure(
                        name,
                        when (state.code) {
                            ErrorCodes.NOT_ACCEPTING -> LinkProblem.NotAccepting
                            ErrorCodes.IDENTITY_MISMATCH -> LinkProblem.WrongDevice
                            else -> LinkProblem.Refused
                        },
                    ),
                )
            }
        }
    }

    private fun endAttempt() {
        attempt?.link?.close()
        attempt = null
    }

    override fun submitCode(code: String) = loop.post {
        attempt?.requester?.submitCode(code)
    }

    override fun cancelPairing() = loop.post {
        attempt?.let {
            it.cancelledHere = true
            it.requester.cancel()
            endAttempt()
        }
        if (flow.value.pairing is PairingUi.ShowCode) target.cancel()
        setPairing(null)
    }

    // ---- pairing: target ----

    private fun onTargetEvent(event: TargetEvent) {
        when (event) {
            is TargetEvent.CodeShown -> {
                targetName = event.name
                setPairing(PairingUi.ShowCode(event.code.display, event.name, event.expiresAtMs))
                expiryTimer?.invoke()
                expiryTimer = loop.postDelayed((event.expiresAtMs - clock.nowMs()).coerceAtLeast(0) + EXPIRY_SLACK_MS) {
                    target.checkExpiry()
                }
            }
            is TargetEvent.CodeHidden -> when (event.reason) {
                HideReason.EXPIRED -> setPairing(PairingUi.Expired(targetName))
                HideReason.CANCELLED -> if (flow.value.pairing is PairingUi.ShowCode) setPairing(null)
                HideReason.USED, HideReason.REPLACED, HideReason.LOCKED, HideReason.FAILED -> Unit
            }
            is TargetEvent.WrongCode -> Unit
            is TargetEvent.Locked -> setPairing(PairingUi.Locked(targetName))
            is TargetEvent.Paired -> {
                val link = targetLink
                val route = link?.route ?: LinkRoute.Wifi
                val seen = link?.connection?.remoteHost?.takeIf { it.isNotEmpty() }
                    ?.let { Endpoint(it, DiligentLink.DEFAULT_PORT).toString() }
                val addresses = when {
                    seen == null -> LastAddresses()
                    route == LinkRoute.Tailscale -> LastAddresses(tailscale = seen)
                    else -> LastAddresses(lan = seen)
                }
                routes[event.record.deviceId] = route
                addPaired(event.record.copy(lastSeen = clock.nowMs(), lastAddresses = addresses))
                setPairing(PairingUi.Paired(event.record.name))
            }
            is TargetEvent.Failed -> setPairing(failure(targetName, LinkProblem.Refused))
        }
    }

    private fun addPaired(record: PairingRecord) {
        registry.add(record)
        identityChanged -= record.deviceId
        storage.saveRegistry(registry)
        publish()
    }

    // ---- paired devices ----

    override fun rename(deviceId: String, name: String) = loop.post {
        val id = DeviceId.parse(deviceId) ?: return@post
        val clean = name.trim().ifEmpty { return@post }
        registry.update(id) { it.copy(name = clean) }
        storage.saveRegistry(registry)
        publish()
    }

    override fun forget(deviceId: String) = loop.post {
        val id = DeviceId.parse(deviceId) ?: return@post
        val record = registry[id] ?: return@post
        // Tell the peer when it is reachable (A.6), then forget here whatever happens.
        val (tries, _) = candidates(record)
        val pin = record.pinnedFingerprint
        if (tries.isNotEmpty() && pin != null && id !in identityChanged) {
            connectFirst(tries, pin) { link, _, _ ->
                link?.outbox?.send(PairForget)
                link?.close()
            }
        }
        forgetLocally(id)
    }

    private fun forgetLocally(id: DeviceId) {
        registry.forget(id)
        storage.saveRegistry(registry)
        storage.forgetBaselines(id.value)
        routes.remove(id)
        identityChanged.remove(id)
        changeSettings { s -> s.copy(autoSync = s.autoSync.filterNot { it.deviceId == id.value }.toSet()) }
        publish()
    }

    override fun setApproval(deviceId: String, approval: Approval) = loop.post {
        val id = DeviceId.parse(deviceId) ?: return@post
        registry.update(id) {
            it.copy(approval = if (approval == Approval.AlwaysAllow) WireApproval.ALWAYS else WireApproval.ASK)
        }
        storage.saveRegistry(registry)
        publish()
    }

    private fun rememberAddress(id: DeviceId, endpoint: Endpoint) {
        registry.update(id) {
            it.copy(
                lastAddresses = if (isTailscale(endpoint.host)) {
                    it.lastAddresses.copy(tailscale = endpoint.toString())
                } else {
                    it.lastAddresses.copy(lan = endpoint.toString())
                },
            )
        }
        storage.saveRegistry(registry)
    }

    // ---- sync ----

    /** One sync in progress, on either side. */
    private inner class SyncJob(
        val deviceId: DeviceId,
        val deviceName: String,
        val projectDir: File,
        val projectId: String,
        val auto: Boolean,
    ) {
        var link: Link? = null
        var session: SyncSession? = null
        var plan: SyncPlan? = null
        var total = 0
        var route: LinkRoute = LinkRoute.Wifi
    }

    private class PendingIncoming(
        val link: Link,
        val hello: SyncHello,
        val record: PairingRecord,
        val targetDir: File?,
    )

    private class ConflictSet(
        val deviceName: String,
        val projectDir: File,
        val copies: MutableMap<String, String>,
        val sent: Int,
        val received: Int,
        val installHint: String?,
    )

    override fun planSync(deviceId: String, projectPath: String) = loop.post { startSync(deviceId, projectPath, auto = false) }

    private fun startSync(deviceId: String, projectPath: String, auto: Boolean) {
        val id = DeviceId.parse(deviceId)
        val record = id?.let { registry[it] }
        val name = record?.name ?: deviceId
        if (job != null || conflicts != null) {
            if (!auto) setSync(SyncUi.Failed(name, host.text(LinkProblem.Busy, name)))
            return
        }
        if (record == null) return setSync(SyncUi.Failed(name, host.text(LinkProblem.NotPaired, name)))
        if (record.deviceId in identityChanged) return setSync(SyncUi.Failed(name, host.text(LinkProblem.IdentityChanged, name)))
        val dir = File(projectPath)
        if (!dir.isDirectory) return setSync(SyncUi.Failed(name, host.text(LinkProblem.NotAProject, name)))
        val projectId = ensureProject(dir)
        val (tries, problem) = candidates(record)
        if (tries.isEmpty()) {
            if (!auto) setSync(SyncUi.Failed(name, host.text(problem ?: LinkProblem.Unreachable, name)))
            return
        }
        val current = SyncJob(record.deviceId, name, dir, projectId, auto)
        job = current
        setSync(SyncUi.Planning(name))
        host.saveAll()
        connectFirst(tries, record.pinnedFingerprint) { link, failed, endpoint ->
            if (job !== current) {
                link?.close()
                return@connectFirst
            }
            if (link == null || endpoint == null) {
                job = null
                if (failed == LinkProblem.IdentityChanged) {
                    identityChanged += record.deviceId
                    publish()
                }
                if (failed == LinkProblem.NotFound || failed == LinkProblem.Unreachable) routes.remove(record.deviceId)
                setSync(SyncUi.Failed(name, host.text(failed ?: LinkProblem.Unreachable, name)))
                publish()
                return@connectFirst
            }
            current.link = link
            current.route = link.route ?: LinkRoute.Wifi
            routes[record.deviceId] = current.route
            rememberAddress(record.deviceId, endpoint)
            val session = newSession(SyncSession.Role.INITIATOR, link, record, current, projectName = dir.name)
            current.session = session
            session.start()
        }
    }

    private fun newSession(role: SyncSession.Role, link: Link, record: PairingRecord, current: SyncJob, projectName: String?): SyncSession {
        val store = ProjectFileStore(current.projectDir, storage.tempDir(current.projectId))
        val rules = IgnoreRules(store, allowEnv = false)
        val snapshotter = object : Snapshotter {
            override fun snapshot(projectId: String, label: String): String = host.snapshot(current.projectDir, label)
        }
        val key = current.projectDir.path
        return SyncSession(
            role = role,
            outbox = link.outbox,
            peer = record,
            projectId = current.projectId,
            projectName = projectName,
            store = store,
            snapshotter = snapshotter,
            rules = rules,
            baseline = storage.loadBaseline(record.deviceId.value, current.projectId),
            localManifest = { ManifestBuilder.build(store, rules, lastLocal[key]).also { lastLocal[key] = it } },
            listener = SyncSession.Listener { onSyncState(current, it) },
        )
    }

    private fun onSyncState(current: SyncJob, state: SyncState) {
        if (job !== current) return
        val name = current.deviceName
        when (state) {
            SyncState.Idle -> Unit
            SyncState.AwaitingManifest, SyncState.AwaitingPlan -> setSync(SyncUi.Planning(name))
            is SyncState.AwaitingApproval -> {
                val link = current.link ?: return
                incoming = PendingIncoming(link, SyncHello(current.projectId, state.projectName), registry[current.deviceId] ?: return, targetDir = null)
                flow.value = flow.value.copy(
                    incomingProject = IncomingProject(state.projectName ?: current.projectDir.name, name, current.projectDir.path, isNew = false),
                )
            }
            is SyncState.PlanReady -> {
                current.plan = state.plan
                // Automatic sync goes ahead by itself unless something needs the user (A.7, Part D 3).
                if (current.auto && state.plan.conflicts.isEmpty() && state.plan.delete.isEmpty()) {
                    current.session?.confirm()
                } else if (state.plan.isEmpty) {
                    current.session?.confirm()
                } else {
                    setSync(
                        SyncUi.Plan(
                            deviceName = name,
                            newerHere = state.plan.send + state.plan.deleteThere,
                            newerThere = state.plan.receive + state.plan.deleteHere,
                            changedOnBoth = state.plan.conflicts.map { it.path },
                            route = current.route,
                        ),
                    )
                }
            }
            is SyncState.AwaitingConfirm -> {
                current.plan = state.plan
                setSync(SyncUi.Planning(name))
            }
            is SyncState.Transferring -> {
                if (state.pendingFiles > current.total) current.total = state.pendingFiles
                setSync(SyncUi.Transferring(name, current.total - state.pendingFiles, current.total))
            }
            is SyncState.Done -> finishSync(current, state)
            is SyncState.Cancelled -> {
                endJob(current)
                setSync(if (state.byPeer) SyncUi.Failed(name, host.text(LinkProblem.Declined, name)) else null)
            }
            is SyncState.Failed -> {
                endJob(current)
                val problem = when {
                    state.code == ErrorCodes.DENIED -> LinkProblem.Declined
                    state.code == SyncSession.SNAPSHOT_FAILED -> LinkProblem.SnapshotFailed
                    state.message == BUSY -> LinkProblem.Busy
                    else -> LinkProblem.SyncFailed
                }
                setSync(SyncUi.Failed(name, host.text(problem, name)))
            }
        }
    }

    private fun finishSync(current: SyncJob, done: SyncState.Done) {
        val result = done.result
        storage.saveBaseline(current.deviceId.value, current.projectId, result.baseline)
        registry.update(current.deviceId) { it.copy(lastSync = clock.nowMs(), lastSeen = clock.nowMs()) }
        storage.saveRegistry(registry)
        autoChanges[AutoSyncKey(current.deviceId.value, current.projectDir.path)]?.lastSyncAt = clock.nowMs()
        endJob(current)
        val plan = current.plan
        val sent = (plan?.send?.size ?: 0) + (plan?.conflicts?.count { it.kind != ConflictKind.DELETED_LOCAL } ?: 0)
        val hint = InstallHints.forPaths(result.received)
        val copies = result.conflicts
            .filter { it.kind == ConflictKind.BOTH_CHANGED && it.copyPath != null }
            .associateTo(LinkedHashMap()) { it.path to it.copyPath!! }
        if (copies.isEmpty()) {
            setSync(SyncUi.Done(current.deviceName, sent, result.received.size, hint))
        } else {
            val set = ConflictSet(current.deviceName, current.projectDir, copies, sent, result.received.size, hint)
            conflicts = set
            showConflicts(set)
        }
        publish()
    }

    private fun endJob(current: SyncJob) {
        current.link?.close()
        if (job === current) job = null
        if (incoming?.link === current.link) {
            incoming = null
            flow.value = flow.value.copy(incomingProject = null)
        }
    }

    private fun showConflicts(set: ConflictSet) {
        val store = ProjectFileStore(set.projectDir, File(set.projectDir, ".unused"))
        setSync(
            SyncUi.Conflicts(
                set.deviceName,
                set.copies.map { (path, copy) -> SyncConflict(path, textOf(store, path), textOf(store, copy)) },
            ),
        )
    }

    override fun confirmSync() = loop.post {
        val current = job ?: return@post
        if (current.session?.state is SyncState.PlanReady) current.session?.confirm()
    }

    override fun resolveConflict(path: String, choice: ConflictChoice) = loop.post {
        val set = conflicts ?: return@post
        val copy = set.copies[path] ?: return@post
        val dir = set.projectDir
        try {
            val store = ProjectFileStore(dir, storage.tempDir("resolve:" + dir.path))
            when (choice) {
                ConflictChoice.KeepMine -> store.delete(copy)
                ConflictChoice.KeepTheirs -> {
                    // Through the temporary file, so the replacement is atomic like a received file.
                    val size = File(dir, copy).length()
                    var offset = 0L
                    store.writeTemp(path, 0, ByteArray(0))
                    while (offset < size) {
                        val chunk = store.read(copy, offset, CHUNK)
                        if (chunk.isEmpty()) break
                        store.writeTemp(path, offset, chunk)
                        offset += chunk.size
                    }
                    store.commitTemp(path, path, File(dir, copy).canExecute())
                    store.delete(copy)
                }
                ConflictChoice.KeepBoth -> Unit
            }
        } catch (e: IOException) {
            setSync(SyncUi.Failed(set.deviceName, host.text(LinkProblem.SyncFailed, set.deviceName)))
            return@post
        }
        set.copies.remove(path)
        if (set.copies.isEmpty()) {
            conflicts = null
            setSync(SyncUi.Done(set.deviceName, set.sent, set.received, set.installHint))
        } else {
            showConflicts(set)
        }
    }

    override fun cancelSync() = loop.post {
        job?.let { current ->
            current.session?.takeIf { !it.state.isFinal }?.cancel()
            endJob(current)
        }
        // Conflicts left unresolved keep both files, which is always safe.
        conflicts = null
        setSync(null)
    }

    /** Responder: a paired device asks to sync a project (A.7). */
    private fun onHello(link: Link, item: Inbound, hello: SyncHello, record: PairingRecord) {
        if (job != null || conflicts != null || incoming != null) {
            link.outbox.replyError(item, ErrorCodes.UNEXPECTED, BUSY)
            return
        }
        host.saveAll()
        val path = findProject(hello.projectId)
        if (path == null) {
            val dir = uniqueFolder(host.projectsDir, incomingFolderName(hello.name))
            incoming = PendingIncoming(link, hello, record, dir)
            flow.value = flow.value.copy(incomingProject = IncomingProject(dir.name, record.name, dir.path, isNew = true))
            return
        }
        // Give the editor a moment to finish saving before the manifest is built.
        loop.postDelayed(SAVE_SETTLE_MS) { if (!link.closed) startResponder(link, hello, record, File(path), approved = false) }
        job = SyncJob(record.deviceId, record.name, File(path), hello.projectId, auto = false).also { it.link = link }
    }

    private fun startResponder(link: Link, hello: SyncHello, record: PairingRecord, dir: File, approved: Boolean) {
        val current = job?.takeIf { it.link === link } ?: SyncJob(record.deviceId, record.name, dir, hello.projectId, auto = false)
            .also { it.link = link }
        job = current
        current.route = link.route ?: LinkRoute.Wifi
        val session = newSession(SyncSession.Role.RESPONDER, link, record, current, projectName = hello.name)
        current.session = session
        session.start()
        if (approved && session.state is SyncState.AwaitingApproval) session.approve()
    }

    override fun answerIncomingProject(accept: Boolean) = loop.post {
        val pending = incoming ?: return@post
        incoming = null
        flow.value = flow.value.copy(incomingProject = null)
        val dir = pending.targetDir
        if (dir == null) {
            // A known project waiting for approval.
            val session = job?.takeIf { it.link === pending.link }?.session ?: return@post
            if (session.state !is SyncState.AwaitingApproval) return@post
            if (accept) session.approve() else session.deny()
            return@post
        }
        if (!accept || pending.link.closed) {
            pending.link.outbox.send(ErrorMessage(ErrorCodes.DENIED))
            pending.link.close()
            return@post
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            pending.link.outbox.send(ErrorMessage(ErrorCodes.UNKNOWN_PROJECT))
            pending.link.close()
            return@post
        }
        projects[pending.hello.projectId] = dir.path
        storage.saveProjects(projects)
        startResponder(pending.link, pending.hello, pending.record, dir, approved = true)
    }

    /** The project ID in `.diligent/project.json`, written first when the folder has none. */
    private fun ensureProject(dir: File): String {
        val file = File(dir, ProjectPaths.PROJECT_FILE)
        val existing = readProjectId(file)
        if (existing != null) {
            projects[existing] = dir.path
            storage.saveProjects(projects)
            return existing
        }
        val id = UUID.randomUUID().toString()
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("projectId", id).put("name", dir.name).toString(2) + "\n")
        projects[id] = dir.path
        storage.saveProjects(projects)
        return id
    }

    private fun readProjectId(file: File): String? = try {
        if (file.isFile) JSONObject(file.readText()).optString("projectId").takeIf { it.isNotBlank() } else null
    } catch (e: JSONException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun findProject(projectId: String): String? {
        projects[projectId]?.let { path ->
            if (readProjectId(File(path, ProjectPaths.PROJECT_FILE)) == projectId) return path
        }
        val found = host.projectsDir.listFiles()?.firstOrNull {
            it.isDirectory && readProjectId(File(it, ProjectPaths.PROJECT_FILE)) == projectId
        } ?: return null
        projects[projectId] = found.path
        storage.saveProjects(projects)
        return found.path
    }

    // ---- automatic sync ----

    private class AutoWatch(var newestMtime: Long = -1, var changedAt: Long? = null, var lastSyncAt: Long = 0)

    override fun setAutoSync(projectPath: String, deviceId: String, on: Boolean) = loop.post {
        val key = AutoSyncKey(DeviceId.parse(deviceId)?.value ?: return@post, projectPath)
        changeSettings { s -> s.copy(autoSync = if (on) s.autoSync + key else s.autoSync - key) }
        if (on) autoChanges[key] = AutoWatch(lastSyncAt = clock.nowMs()) else autoChanges.remove(key)
        scheduleAuto()
    }

    private fun scheduleAuto() {
        autoTimer?.invoke()
        autoTimer = null
        if (settings.autoSync.isEmpty()) return
        autoTimer = loop.postDelayed(AUTO_TICK_MS) {
            autoTick()
            scheduleAuto()
        }
    }

    /** Runs due automatic syncs: 30 s after the last change, or every 10 minutes, only when reachable. */
    internal fun autoTick() {
        val now = clock.nowMs()
        for (key in settings.autoSync) {
            val watch = autoChanges.getOrPut(key) { AutoWatch(lastSyncAt = now) }
            val dir = File(key.projectPath)
            if (!dir.isDirectory) continue
            val newest = newestMtime(dir)
            if (watch.newestMtime >= 0 && newest != watch.newestMtime) watch.changedAt = now
            watch.newestMtime = newest
            if (job != null || conflicts != null || incoming != null) continue
            val id = DeviceId.parse(key.deviceId) ?: continue
            if (routes[id] == null) continue
            val changed = watch.changedAt
            val quiet = changed != null && changed > watch.lastSyncAt && now - changed >= AUTO_QUIET_MS
            val scheduled = now - watch.lastSyncAt >= AUTO_INTERVAL_MS
            if (quiet || scheduled) {
                watch.lastSyncAt = now
                watch.changedAt = null
                startSync(key.deviceId, key.projectPath, auto = true)
                return
            }
        }
    }

    private fun newestMtime(dir: File): Long {
        val store = ProjectFileStore(dir, File(dir, ".unused"))
        val rules = IgnoreRules(store)
        return try {
            store.list(PathFilter { path, isDirectory -> !rules.isIgnored(path, isDirectory) }).maxOfOrNull { it.mtimeMs } ?: 0
        } catch (e: IOException) {
            0
        }
    }

    // ---- state ----

    override fun dismiss() = loop.post {
        val pairing = flow.value.pairing
        if (pairing !is PairingUi.ShowCode && pairing !is PairingUi.EnterCode && pairing !is PairingUi.Requesting) setPairing(null)
        when (flow.value.sync) {
            is SyncUi.Done, is SyncUi.Failed -> setSync(null)
            else -> Unit
        }
    }

    private fun changeSettings(change: (LinkSettings) -> LinkSettings) {
        settings = change(settings)
        storage.saveSettings(settings)
        flow.value = flow.value.copy(
            visibility = settings.visibility,
            stayReachable = settings.stayReachable,
            connectionMode = settings.connectionMode,
        )
        publish()
    }

    private fun setPairing(pairing: PairingUi?) {
        flow.value = flow.value.copy(pairing = pairing)
    }

    private fun setSync(sync: SyncUi?) {
        flow.value = flow.value.copy(sync = sync)
    }

    private fun failure(name: String, problem: LinkProblem): PairingUi =
        if (problem == LinkProblem.IdentityChanged) PairingUi.IdentityChanged(name) else PairingUi.Failed(name, host.text(problem, name))

    private fun publish() {
        val paired = registry.records
        val pairedIds = paired.map { it.deviceId }.toSet()
        flow.value = flow.value.copy(
            myDevices = paired.map { record ->
                val blocked = record.deviceId in identityChanged
                PairedDevice(
                    deviceId = record.deviceId.value,
                    name = record.name,
                    route = if (blocked) null else routes[record.deviceId],
                    lastSyncMs = record.lastSync,
                    approval = if (record.approval == WireApproval.ALWAYS) Approval.AlwaysAllow else Approval.AskEachTime,
                    identityChanged = blocked,
                    autoSyncProjects = settings.autoSync.filter { it.deviceId == record.deviceId.value }.map { it.projectPath }.toSet(),
                )
            },
            nearby = found.values
                .filter { it.info.deviceId !in pairedIds && it.info.acceptsPairing }
                .map { NearbyDevice(it.info.deviceId.value, it.info.name, it.info.platform.wireName) },
        )
    }

    private fun routeOf(endpoint: Endpoint) = if (isTailscale(endpoint.host)) LinkRoute.Tailscale else LinkRoute.Wifi

    companion object {
        const val SAVE_SETTLE_MS = 500L
        const val AUTO_TICK_MS = 10_000L
        const val AUTO_QUIET_MS = 30_000L
        const val AUTO_INTERVAL_MS = 10 * 60_000L
        private const val EXPIRY_SLACK_MS = 50L
        private const val CHUNK = 256 * 1024
        private const val BUSY = "busy"
        private const val TEXT_LIMIT = 512 * 1024

        /** True for an address in Tailscale's `100.64.0.0/10`. */
        fun isTailscale(host: String): Boolean {
            if (host.isEmpty() || !host.all { it.isDigit() || it == '.' }) return false
            return try {
                TailscaleAddress.inRange(InetAddress.getByName(host))
            } catch (e: IOException) {
                false
            }
        }

        /** The file as text for the compare screen, or null when it is binary or too large. */
        internal fun textOf(store: ProjectFileStore, path: String): String? = try {
            val bytes = store.read(path, 0, TEXT_LIMIT + 1)
            if (bytes.size > TEXT_LIMIT || bytes.any { it == 0.toByte() }) {
                null
            } else {
                val decoder = Charsets.UTF_8.newDecoder()
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        } catch (e: IOException) {
            // Includes CharacterCodingException: not UTF-8, so shown as binary.
            null
        }
    }
}
