package com.vscodroid.diligent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vscodroid.R
import com.vscodroid.diligent.MobileActions
import com.vscodroid.diligent.MobileShell
import com.vscodroid.diligent.devices.Approval
import com.vscodroid.diligent.devices.ConnectionMode
import com.vscodroid.diligent.devices.DeviceLink
import com.vscodroid.diligent.devices.DevicesState
import com.vscodroid.diligent.devices.LinkRoute
import com.vscodroid.diligent.devices.NearbyDevice
import com.vscodroid.diligent.devices.PairedDevice
import com.vscodroid.diligent.devices.PreviewDeviceLink
import com.vscodroid.diligent.devices.Visibility
import com.vscodroid.diligent.ui.components.QrCode
import com.vscodroid.diligent.ui.components.SectionHeader
import com.vscodroid.diligent.ui.components.SheetButtons
import com.vscodroid.diligent.ui.components.relativeTimeText
import com.vscodroid.diligent.ui.theme.DiligentTheme
import kotlinx.coroutines.delay

/** How often "Last sync: 5 minutes ago" is brought up to date. */
private const val CLOCK_TICK_MS = 30_000L

/**
 * More, then Devices: the devices paired before, the ones nearby, adding
 * one by QR code or address, and how this phone can be found (Phase 5,
 * Part C). Everything goes through [MobileActions.devices]; this screen
 * holds only which sheet is open.
 *
 * While it is on screen (resumed) it tells the link so, which is what
 * "Visible: Only while this screen is open" means. The pairing and Sync
 * sheets are not drawn here but in Mobile view's native layer, so a code
 * request or a sync started from the top bar shows on any tab.
 */
@Composable
fun DevicesScreen(shell: MobileShell, actions: MobileActions, modifier: Modifier = Modifier) {
    val link = actions.devices
    val state by link.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(link) {
        link.screenOpen(true)
        onPauseOrDispose { link.screenOpen(false) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    DevicesContent(state, link, shell.folder, now, modifier)
}

@Composable
internal fun DevicesContent(
    state: DevicesState,
    link: DeviceLink,
    projectPath: String?,
    now: Long,
    modifier: Modifier = Modifier,
) {
    var openDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DevicesBody(
                stringResource(R.string.diligent_devices_intro),
                Modifier.padding(start = DiligentTheme.spacing.m, end = DiligentTheme.spacing.m, top = DiligentTheme.spacing.m),
            )

            SectionHeader(stringResource(R.string.diligent_devices_mine))
            if (state.myDevices.isEmpty()) {
                DevicesBody(stringResource(R.string.diligent_devices_mine_empty), Modifier.padding(horizontal = DiligentTheme.spacing.m))
            }
            for (device in state.myDevices) {
                PairedRow(
                    device = device,
                    now = now,
                    canSyncHere = projectPath != null,
                    onOpen = { openDevice = device.deviceId },
                    onSync = { projectPath?.let { link.planSync(device.deviceId, it) } },
                )
            }

            SectionHeader(stringResource(R.string.diligent_devices_nearby))
            DevicesBody(
                stringResource(if (state.nearby.isEmpty()) R.string.diligent_devices_nearby_empty else R.string.diligent_devices_nearby_body),
                Modifier.padding(horizontal = DiligentTheme.spacing.m),
            )
            for (device in state.nearby) {
                NearbyRow(device, onConnect = { link.connect(device.deviceId) })
            }
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.m)
                    .heightIn(min = DiligentTheme.sizes.touchTarget),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(DiligentTheme.sizes.iconSmall))
                Text(stringResource(R.string.diligent_devices_add), Modifier.padding(start = DiligentTheme.spacing.s))
            }

            HorizontalDivider(thickness = DiligentTheme.sizes.divider, color = DiligentTheme.colors.outlineVariant)
            SectionHeader(stringResource(R.string.diligent_devices_section_settings))
            ReachSettings(state, link)
            if (state.myName.isNotEmpty() || state.myDeviceId.isNotEmpty()) {
                Column(Modifier.padding(DiligentTheme.spacing.m)) {
                    if (state.myName.isNotEmpty()) DevicesBody(stringResource(R.string.diligent_devices_this_phone, state.myName))
                    if (state.myDeviceId.isNotEmpty()) {
                        SelectionContainer { DevicesBody(stringResource(R.string.diligent_devices_this_id, state.myDeviceId)) }
                    }
                }
            }
        }
    }
    state.myDevices.firstOrNull { it.deviceId == openDevice }?.let { device ->
        DeviceSheet(device, link, projectPath, now, onDismiss = { openDevice = null })
    }
    if (adding) AddDeviceSheet(link, onDismiss = { adding = false })
}

/** Visibility, staying reachable, and which connections to use. */
@Composable
private fun ReachSettings(state: DevicesState, link: DeviceLink) {
    DevicesSubheading(stringResource(R.string.diligent_devices_visibility))
    Column(Modifier.selectableGroup()) {
        for (option in listOf(Visibility.On, Visibility.Off, Visibility.WhileScreenOpen)) {
            ChoiceRow(
                title = stringResource(option.titleRes()),
                body = stringResource(option.bodyRes()),
                selected = state.visibility == option,
                onSelect = { link.setVisibility(option) },
            )
        }
    }
    SwitchRow(
        title = stringResource(R.string.diligent_devices_stay_reachable),
        body = stringResource(R.string.diligent_devices_stay_reachable_body),
        checked = state.stayReachable,
        onChange = link::setStayReachable,
    )
    DevicesSubheading(stringResource(R.string.diligent_devices_connections))
    Column(Modifier.selectableGroup()) {
        for (mode in ConnectionMode.entries) {
            ChoiceRow(
                title = stringResource(mode.titleRes()),
                body = stringResource(mode.bodyRes()),
                selected = state.connectionMode == mode,
                onSelect = { link.setConnectionMode(mode) },
            )
        }
    }
}

/** A paired device: name, how it is reached, the last sync, and Sync when it can. */
@Composable
private fun PairedRow(device: PairedDevice, now: Long, canSyncHere: Boolean, onOpen: () -> Unit, onSync: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .clickable(onClick = onOpen)
            .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Icon(
            if (device.identityChanged) Icons.Outlined.ErrorOutline else Icons.Outlined.Devices,
            contentDescription = null,
            tint = if (device.identityChanged) DiligentTheme.colors.error else DiligentTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(DiligentTheme.sizes.icon),
        )
        Column(Modifier.weight(1f)) {
            Text(device.name, style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.onSurface)
            DeviceStatus(device, now)
        }
        if (canSync(device) && canSyncHere) {
            OutlinedButton(onClick = onSync, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_devices_sync))
            }
        }
    }
}

/** "Online, Wi-Fi", "Last sync: 5 minutes ago", or the identity change that blocks it. */
@Composable
private fun DeviceStatus(device: PairedDevice, now: Long) {
    if (device.identityChanged) {
        Text(
            stringResource(R.string.diligent_devices_blocked),
            style = DiligentTheme.type.material.bodySmall,
            color = DiligentTheme.colors.error,
        )
        Text(
            stringResource(R.string.diligent_devices_identity_changed),
            style = DiligentTheme.type.material.bodySmall,
            color = DiligentTheme.colors.onSurfaceVariant,
        )
        return
    }
    Text(
        stringResource(statusRes(device.route)),
        style = DiligentTheme.type.material.bodySmall,
        color = if (device.route != null) DiligentTheme.colors.running else DiligentTheme.colors.onSurfaceVariant,
    )
    Text(
        device.lastSyncMs?.let { stringResource(R.string.diligent_devices_last_sync, relativeTimeText(it, now)) }
            ?: stringResource(R.string.diligent_devices_never_synced),
        style = DiligentTheme.type.material.bodySmall,
        color = DiligentTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun NearbyRow(device: NearbyDevice, onConnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Icon(Icons.Outlined.Devices, contentDescription = null, tint = DiligentTheme.colors.onSurfaceVariant, modifier = Modifier.size(DiligentTheme.sizes.icon))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.onSurface)
            if (device.platform.isNotEmpty()) {
                Text(device.platform, style = DiligentTheme.type.material.bodySmall, color = DiligentTheme.colors.onSurfaceVariant)
            }
        }
        val label = stringResource(R.string.diligent_devices_connect_to, device.name)
        Button(
            onClick = onConnect,
            modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget).semantics { contentDescription = label },
        ) { Text(stringResource(R.string.diligent_devices_connect)) }
    }
}

/** One paired device: Sync, Rename, Forget, approval and automatic sync. */
@Composable
private fun DeviceSheet(device: PairedDevice, link: DeviceLink, projectPath: String?, now: Long, onDismiss: () -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(device.deviceId) { mutableStateOf(device.name) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    DiligentSheet(onDismiss = onDismiss) { hide ->
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DiligentTheme.spacing.l)
                .padding(bottom = DiligentTheme.spacing.l)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            DevicesTitle(device.name)
            DeviceStatus(device, now)
            if (!device.identityChanged) {
                Button(
                    onClick = { hide { projectPath?.let { link.planSync(device.deviceId, it) } } },
                    enabled = canSync(device) && projectPath != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
                ) { Text(stringResource(R.string.diligent_devices_sync_with, device.name)) }
                if (projectPath == null) DevicesBody(stringResource(R.string.diligent_devices_no_project))
            }
            if (renaming) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.diligent_devices_rename_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                SheetButtons(
                    confirm = stringResource(R.string.diligent_devices_save),
                    confirmEnabled = name.isNotBlank(),
                    onConfirm = {
                        link.rename(device.deviceId, name.trim())
                        renaming = false
                    },
                    onCancel = {
                        name = device.name
                        renaming = false
                    },
                    cancel = stringResource(R.string.diligent_devices_cancel),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
                    OutlinedButton(
                        onClick = { renaming = true },
                        modifier = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget),
                    ) { Text(stringResource(R.string.diligent_devices_rename)) }
                    OutlinedButton(
                        onClick = { confirmForget = true },
                        modifier = Modifier.weight(1f).heightIn(min = DiligentTheme.sizes.touchTarget),
                    ) { Text(stringResource(R.string.diligent_devices_forget), color = DiligentTheme.colors.error) }
                }
            }
            if (!device.identityChanged) {
                DevicesSubheading(stringResource(R.string.diligent_devices_approval), inset = false)
                Column(Modifier.selectableGroup()) {
                    for (approval in listOf(Approval.AlwaysAllow, Approval.AskEachTime)) {
                        ChoiceRow(
                            title = stringResource(
                                if (approval == Approval.AlwaysAllow) R.string.diligent_devices_approval_always
                                else R.string.diligent_devices_approval_ask,
                            ),
                            body = null,
                            selected = device.approval == approval,
                            onSelect = { link.setApproval(device.deviceId, approval) },
                            inset = false,
                        )
                    }
                }
                val project = projectPath?.substringAfterLast('/')
                SwitchRow(
                    title = project?.let { stringResource(R.string.diligent_devices_auto_sync, it) }
                        ?: stringResource(R.string.diligent_devices_auto_sync_none),
                    body = stringResource(if (projectPath != null) R.string.diligent_devices_auto_sync_body else R.string.diligent_devices_no_project),
                    checked = projectPath != null && projectPath in device.autoSyncProjects,
                    enabled = projectPath != null,
                    onChange = { on -> projectPath?.let { link.setAutoSync(it, device.deviceId, on) } },
                    inset = false,
                )
            }
            TextButton(
                onClick = { hide {} },
                modifier = Modifier.align(Alignment.End).heightIn(min = DiligentTheme.sizes.touchTarget),
            ) { Text(stringResource(R.string.diligent_devices_close)) }
        }
        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text(stringResource(R.string.diligent_devices_forget_title, device.name)) },
                text = { Text(stringResource(R.string.diligent_devices_forget_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForget = false
                        hide { link.forget(device.deviceId) }
                    }) { Text(stringResource(R.string.diligent_devices_forget), color = DiligentTheme.colors.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.diligent_devices_cancel)) }
                },
            )
        }
    }
}

/** This phone's QR code, and a form for the other device's address. */
@Composable
private fun AddDeviceSheet(link: DeviceLink, onDismiss: () -> Unit) {
    val qr = remember(link) { link.pairingQr() }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(DEFAULT_LINK_PORT.toString()) }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val problem = AddressForm.problem(host, port, deviceId)
    DiligentSheet(onDismiss = onDismiss) { hide ->
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DiligentTheme.spacing.l)
                .padding(bottom = DiligentTheme.spacing.l)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) {
            DevicesTitle(stringResource(R.string.diligent_devices_add_title))
            if (qr != null) {
                DevicesBody(stringResource(R.string.diligent_devices_add_qr_body))
                QrCode(
                    qr,
                    stringResource(R.string.diligent_devices_add_qr),
                    DiligentTheme.sizes.qrCode,
                    Modifier.align(Alignment.CenterHorizontally),
                )
                SelectionContainer {
                    Text(qr, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurfaceVariant)
                }
            } else {
                DevicesBody(stringResource(R.string.diligent_devices_add_qr_none))
            }
            DevicesTitle(stringResource(R.string.diligent_devices_add_address))
            DevicesBody(stringResource(R.string.diligent_devices_add_address_body))
            OutlinedTextField(
                value = host,
                onValueChange = { typed ->
                    // A pasted diligent:// code fills all three fields.
                    val code = AddressForm.fromQr(typed)
                    if (code != null) {
                        host = code.host
                        port = code.port.toString()
                        deviceId = code.deviceId.orEmpty()
                    } else {
                        host = typed
                    }
                },
                label = { Text(stringResource(R.string.diligent_devices_add_host)) },
                singleLine = true,
                isError = tried && (problem == AddressProblem.EmptyHost || problem == AddressProblem.BadHost),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.diligent_devices_add_port)) },
                singleLine = true,
                isError = tried && problem == AddressProblem.BadPort,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text(stringResource(R.string.diligent_devices_add_id)) },
                singleLine = true,
                isError = tried && problem == AddressProblem.BadDeviceId,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (tried && problem != null) DevicesNotice(stringResource(problem.messageRes()))
            SheetButtons(
                confirm = stringResource(R.string.diligent_devices_connect),
                confirmEnabled = true,
                onConfirm = {
                    tried = true
                    AddressForm.parse(host, port, deviceId)?.let { address ->
                        hide { link.connectAddress(address.host, address.port, address.deviceId) }
                    }
                },
                onCancel = { hide {} },
                cancel = stringResource(R.string.diligent_devices_cancel),
            )
        }
    }
}

// Small pieces shared by the Devices, pairing and Sync sheets.

@Composable
internal fun DevicesTitle(text: String, modifier: Modifier = Modifier) = Text(
    text,
    modifier = modifier.semantics { heading() },
    style = DiligentTheme.type.material.titleMedium,
    color = DiligentTheme.colors.onSurface,
)

@Composable
internal fun DevicesBody(text: String, modifier: Modifier = Modifier, color: Color = DiligentTheme.colors.onSurfaceVariant) = Text(
    text,
    modifier = modifier,
    style = DiligentTheme.type.material.bodyMedium,
    color = color,
)

@Composable
internal fun DevicesNotice(text: String) = Text(
    text,
    style = DiligentTheme.type.material.bodyMedium,
    color = DiligentTheme.colors.error,
)

@Composable
internal fun DevicesCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
            content = content,
        )
    }
}

@Composable
private fun DevicesSubheading(text: String, inset: Boolean = true) = Text(
    text,
    modifier = Modifier
        .semantics { heading() }
        .padding(horizontal = if (inset) DiligentTheme.spacing.m else DiligentTheme.spacing.xs)
        .padding(top = DiligentTheme.spacing.s),
    style = DiligentTheme.type.material.labelLarge,
    color = DiligentTheme.colors.onSurface,
)

/** A radio choice; the whole row selects it. */
@Composable
private fun ChoiceRow(title: String, body: String?, selected: Boolean, onSelect: () -> Unit, inset: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = if (inset) DiligentTheme.spacing.m else 0.dp, vertical = DiligentTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = DiligentTheme.type.material.bodyLarge, color = DiligentTheme.colors.onSurface)
            if (body != null) {
                Text(body, style = DiligentTheme.type.material.bodySmall, color = DiligentTheme.colors.onSurfaceVariant)
            }
        }
    }
}

/** A setting that is on or off; the whole row toggles, the switch only shows it. */
@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    inset: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DiligentTheme.sizes.touchTarget + DiligentTheme.spacing.s)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = if (inset) DiligentTheme.spacing.m else 0.dp, vertical = DiligentTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = DiligentTheme.type.material.bodyLarge,
                color = if (enabled) DiligentTheme.colors.onSurface else DiligentTheme.colors.disabled,
            )
            Text(body, style = DiligentTheme.type.material.bodySmall, color = DiligentTheme.colors.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun Visibility.titleRes(): Int = when (this) {
    Visibility.On -> R.string.diligent_devices_visibility_on
    Visibility.Off -> R.string.diligent_devices_visibility_off
    Visibility.WhileScreenOpen -> R.string.diligent_devices_visibility_screen
}

private fun Visibility.bodyRes(): Int = when (this) {
    Visibility.On -> R.string.diligent_devices_visibility_on_body
    Visibility.Off -> R.string.diligent_devices_visibility_off_body
    Visibility.WhileScreenOpen -> R.string.diligent_devices_visibility_screen_body
}

private fun ConnectionMode.titleRes(): Int = when (this) {
    ConnectionMode.Automatic -> R.string.diligent_devices_conn_auto
    ConnectionMode.WifiOrHotspot -> R.string.diligent_devices_conn_wifi
    ConnectionMode.TailscaleOnly -> R.string.diligent_devices_conn_tailscale
    ConnectionMode.PhoneToPhone -> R.string.diligent_devices_conn_phone
}

private fun ConnectionMode.bodyRes(): Int = when (this) {
    ConnectionMode.Automatic -> R.string.diligent_devices_conn_auto_body
    ConnectionMode.WifiOrHotspot -> R.string.diligent_devices_conn_wifi_body
    ConnectionMode.TailscaleOnly -> R.string.diligent_devices_conn_tailscale_body
    ConnectionMode.PhoneToPhone -> R.string.diligent_devices_conn_phone_body
}

// -- Previews --

internal const val PREVIEW_DEVICES_NOW = 1_800_000_000_000L
private const val PREVIEW_MINUTE = 60_000L

internal val PreviewDevicesState = DevicesState(
    myName = "Toto's Pixel",
    myDeviceId = "ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23-4567",
    myDevices = listOf(
        PairedDevice("LAPTOP", "Toto's Laptop", LinkRoute.Wifi, PREVIEW_DEVICES_NOW - 5 * PREVIEW_MINUTE, Approval.AlwaysAllow),
        PairedDevice("TAB", "Galaxy Tab", null, PREVIEW_DEVICES_NOW - 30 * 60 * PREVIEW_MINUTE, Approval.AskEachTime),
        PairedDevice("OLD", "Work PC", LinkRoute.Tailscale, null, Approval.AskEachTime, identityChanged = true),
    ),
    nearby = listOf(NearbyDevice("AIR", "MacBook-Air", "macos")),
)

@Composable
private fun DevicesSample(state: DevicesState) = DevicesContent(
    state = state,
    link = PreviewDeviceLink(state),
    projectPath = "/home/projects/hello-next",
    now = PREVIEW_DEVICES_NOW,
)

@Preview(name = "Devices, light", widthDp = 360, heightDp = 1400)
@Composable
private fun DevicesLightPreview() = DiligentTheme(darkTheme = false) { DevicesSample(PreviewDevicesState) }

@Preview(name = "Devices, dark", widthDp = 360, heightDp = 1400)
@Composable
private fun DevicesDarkPreview() = DiligentTheme(darkTheme = true) { DevicesSample(PreviewDevicesState) }

@Preview(name = "Devices, empty, dark", widthDp = 360, heightDp = 1000)
@Composable
private fun DevicesEmptyPreview() = DiligentTheme(darkTheme = true) { DevicesSample(DevicesState()) }

@Preview(name = "Devices, large font", widthDp = 360, heightDp = 2000, fontScale = 2f)
@Composable
private fun DevicesLargeFontPreview() = DiligentTheme(darkTheme = false) { DevicesSample(PreviewDevicesState) }
