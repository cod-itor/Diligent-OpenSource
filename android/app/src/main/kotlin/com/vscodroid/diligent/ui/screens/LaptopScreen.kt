package com.vscodroid.diligent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.bridge.generateSshKeyIn
import com.vscodroid.diligent.laptop.LaptopSsh
import com.vscodroid.diligent.laptop.LaptopTest
import com.vscodroid.diligent.laptop.Machine
import com.vscodroid.diligent.laptop.MachineOs
import com.vscodroid.diligent.laptop.MachineProblem
import com.vscodroid.diligent.laptop.Machines
import com.vscodroid.diligent.laptop.SshFailure
import com.vscodroid.diligent.process.BundledShell
import com.vscodroid.diligent.ui.components.StatusLabel
import com.vscodroid.diligent.ui.components.StatusTone
import com.vscodroid.diligent.ui.theme.DiligentTheme
import com.vscodroid.util.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A Test under way, or its answer. */
private sealed interface TestState {
    data object Running : TestState
    data class Done(val result: LaptopTest) : TestState
}

/**
 * The Laptop screen (More, then Laptop): what the laptop needs (SSH on), the
 * phone's key with the line that makes the laptop accept it, and the laptops
 * themselves, each with a Test that runs the same ssh a run will.
 *
 * The key is the one VSCodroid's bridge makes for the terminal
 * (`~/.ssh/id_ed25519`), created or reused by the same Kotlin code; nothing
 * here asks for or stores a password. Laptops live in `~/.diligent/machines.json`.
 */
@Composable
fun LaptopScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = remember { File(Environment.getHomeDir(context)) }
    var machines by remember { mutableStateOf<List<Machine>>(emptyList()) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var keyBusy by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val tests = remember { mutableStateMapOf<String, TestState>() }

    LaunchedEffect(Unit) {
        machines = withContext(Dispatchers.IO) { Machines.load(home) }
        publicKey = withContext(Dispatchers.IO) {
            File(home, ".ssh/id_ed25519.pub").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        }
    }

    fun persist(next: List<Machine>) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    Machines.save(home, next)
                    true
                } catch (_: IOException) {
                    false
                }
            }
            saveFailed = !ok
            if (ok) machines = next
        }
    }

    LaptopContent(
        machines = machines,
        publicKey = publicKey,
        keyError = keyError,
        keyBusy = keyBusy,
        saveFailed = saveFailed,
        tests = tests,
        onMakeKey = {
            keyBusy = true
            scope.launch {
                val answer = withContext(Dispatchers.IO) { generateSshKeyIn(context, KEY_COMMENT) }
                keyBusy = false
                try {
                    val json = JSONObject(answer)
                    if (json.optBoolean("success")) {
                        publicKey = json.optString("publicKey").trim()
                        keyError = null
                    } else {
                        keyError = json.optString("error")
                    }
                } catch (e: JSONException) {
                    keyError = e.message
                }
            }
        },
        onCopy = { copy(context, it) },
        onSave = { old, machine -> persist(machines.map { if (it == old) machine else it }.let { if (old == null) it + machine else it }) },
        onRemove = { machine ->
            tests.remove(machine.name)
            persist(machines - machine)
        },
        onTest = { machine ->
            tests[machine.name] = TestState.Running
            scope.launch {
                val result = try {
                    val r = BundledShell(context).run(LaptopSsh.test(machine), home)
                    LaptopSsh.parseTest(r.exitCode, r.lastLines)
                } catch (e: IOException) {
                    LaptopTest.Failed(SshFailure.Unreachable, listOfNotNull(e.message))
                }
                tests[machine.name] = TestState.Done(result)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun LaptopContent(
    machines: List<Machine>,
    publicKey: String?,
    keyError: String?,
    keyBusy: Boolean,
    saveFailed: Boolean,
    tests: Map<String, TestState>,
    onMakeKey: () -> Unit,
    onCopy: (String) -> Unit,
    onSave: (old: Machine?, machine: Machine) -> Unit,
    onRemove: (Machine) -> Unit,
    onTest: (Machine) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxSize(), color = DiligentTheme.colors.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.m),
        ) {
            Body(stringResource(R.string.diligent_laptop_intro))

            Card {
                Heading(stringResource(R.string.diligent_laptop_step_ssh))
                Body(stringResource(R.string.diligent_laptop_ssh_macos))
                Body(stringResource(R.string.diligent_laptop_ssh_linux))
                Body(stringResource(R.string.diligent_laptop_ssh_address))
            }

            Card {
                Heading(stringResource(R.string.diligent_laptop_step_key))
                if (publicKey == null) {
                    Body(stringResource(R.string.diligent_laptop_key_none))
                } else {
                    Code(publicKey)
                    OutlinedButton(onClick = { onCopy(publicKey) }, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                        Text(stringResource(R.string.diligent_laptop_copy_key))
                    }
                    Body(stringResource(R.string.diligent_laptop_key_command))
                    val command = LaptopSsh.authorizeCommand(publicKey)
                    Code(command)
                    OutlinedButton(onClick = { onCopy(command) }, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                        Text(stringResource(R.string.diligent_laptop_copy_command))
                    }
                }
                if (publicKey == null) {
                    Button(onClick = onMakeKey, enabled = !keyBusy, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                        Text(stringResource(R.string.diligent_laptop_make_key))
                    }
                }
                keyError?.let { Text(it, style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.failed) }
            }

            Text(
                stringResource(R.string.diligent_laptop_step_machines),
                modifier = Modifier.semantics { heading() },
                style = DiligentTheme.type.material.titleSmall,
                color = DiligentTheme.colors.primary,
            )
            if (machines.isEmpty() && !adding) Body(stringResource(R.string.diligent_laptop_none))
            for (m in machines) {
                if (editing == m.name) {
                    MachineForm(m, machines - m, onSave = { onSave(m, it); editing = null }, onCancel = { editing = null })
                } else {
                    MachineCard(m, tests[m.name], onTest = { onTest(m) }, onEdit = { editing = m.name; adding = false }, onRemove = { onRemove(m) })
                }
            }
            if (adding) {
                MachineForm(null, machines, onSave = { onSave(null, it); adding = false }, onCancel = { adding = false })
            } else {
                Button(
                    onClick = { adding = true; editing = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = DiligentTheme.sizes.touchTarget),
                ) { Text(stringResource(R.string.diligent_laptop_add)) }
            }
            if (saveFailed) Text(stringResource(R.string.diligent_laptop_save_failed), color = DiligentTheme.colors.failed)
            Body(stringResource(R.string.diligent_laptop_how_to_run))
        }
    }
}

@Composable
private fun MachineCard(machine: Machine, test: TestState?, onTest: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card {
        Text(
            machine.name,
            modifier = Modifier.semantics { heading() },
            style = DiligentTheme.type.material.titleSmall,
            color = DiligentTheme.colors.onSurface,
        )
        Body(
            stringResource(
                R.string.diligent_laptop_machine_line,
                machine.target,
                stringResource(if (machine.os == MachineOs.MacOs) R.string.diligent_laptop_os_macos else R.string.diligent_laptop_os_linux),
            ),
        )
        Code(LaptopSsh.test(machine))
        when (test) {
            null -> Unit
            TestState.Running -> CircularProgressIndicator(Modifier.heightIn(max = DiligentTheme.sizes.touchTarget))
            is TestState.Done -> TestResult(machine, test.result)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTest, enabled = test != TestState.Running, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_laptop_test))
            }
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_laptop_edit))
            }
            TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_laptop_remove))
            }
        }
    }
}

@Composable
private fun TestResult(machine: Machine, result: LaptopTest) {
    when (result) {
        is LaptopTest.Connected -> {
            StatusLabel(stringResource(R.string.diligent_laptop_connected, result.system), StatusTone.Running)
            val found = stringResource(R.string.diligent_laptop_found)
            val missing = stringResource(R.string.diligent_laptop_missing)
            Body(stringResource(R.string.diligent_laptop_tools, if (result.node) found else missing, if (result.git) found else missing))
            if (!result.git) Body(stringResource(R.string.diligent_laptop_needs_git))
        }
        is LaptopTest.Failed -> {
            val text = when (result.failure) {
                SshFailure.Unreachable -> stringResource(R.string.diligent_laptop_unreachable, machine.name)
                SshFailure.KeyRefused -> stringResource(R.string.diligent_laptop_key_refused, machine.name)
                SshFailure.HostKeyChanged -> stringResource(R.string.diligent_laptop_host_key_changed, machine.name)
            }
            Text(text, style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.failed)
            if (result.output.isNotEmpty()) {
                Text(result.output.takeLast(6).joinToString("\n"), style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MachineForm(initial: Machine?, others: List<Machine>, onSave: (Machine) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(initial?.user.orEmpty()) }
    var os by rememberSaveable { mutableStateOf(initial?.os ?: MachineOs.MacOs) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val machine = Machine(name.trim(), host.trim(), user.trim(), os)
    val problem = if (tried) Machines.problem(machine, others) else null
    Card {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.diligent_laptop_form_name)) },
            singleLine = true,
            isError = problem == MachineProblem.NoName || problem == MachineProblem.BadName || problem == MachineProblem.Duplicate,
            supportingText = when (problem) {
                MachineProblem.NoName, MachineProblem.BadName -> ({ Text(stringResource(R.string.diligent_laptop_form_bad_name)) })
                MachineProblem.Duplicate -> ({ Text(stringResource(R.string.diligent_laptop_form_duplicate)) })
                else -> null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.diligent_laptop_form_host)) },
            singleLine = true,
            textStyle = DiligentTheme.type.code,
            isError = problem == MachineProblem.BadHost,
            supportingText = { Text(stringResource(R.string.diligent_laptop_form_host_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(stringResource(R.string.diligent_laptop_form_user)) },
            singleLine = true,
            textStyle = DiligentTheme.type.code,
            isError = problem == MachineProblem.BadUser,
            supportingText = { Text(stringResource(R.string.diligent_laptop_form_user_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
            for (choice in MachineOs.entries) {
                FilterChip(
                    selected = os == choice,
                    onClick = { os = choice },
                    label = { Text(stringResource(if (choice == MachineOs.MacOs) R.string.diligent_laptop_os_macos else R.string.diligent_laptop_os_linux)) },
                    modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s)) {
            Button(
                onClick = {
                    tried = true
                    if (Machines.problem(machine, others) == null) onSave(machine)
                },
                modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget),
            ) { Text(stringResource(R.string.diligent_laptop_form_save)) }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = DiligentTheme.sizes.touchTarget)) {
                Text(stringResource(R.string.diligent_run_form_cancel))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = DiligentTheme.colors.surfaceContainer, shape = DiligentTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(DiligentTheme.spacing.s),
        ) { content() }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = DiligentTheme.type.material.titleSmall, color = DiligentTheme.colors.onSurface)
}

@Composable
private fun Body(text: String) {
    Text(text, style = DiligentTheme.type.material.bodyMedium, color = DiligentTheme.colors.onSurfaceVariant)
}

@Composable
private fun Code(text: String) {
    Text(text, style = DiligentTheme.type.codeSmall, color = DiligentTheme.colors.onSurface)
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ssh", text))
    // Android 13+ confirms a copy itself; earlier versions say nothing.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.diligent_laptop_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The comment a key made here carries: no name or address of the user. */
private const val KEY_COMMENT = "diligent@android"

private val previewMachine = Machine("mac", "192.168.1.20", "me", MachineOs.MacOs)

@Preview(name = "Laptop, light", widthDp = 360, heightDp = 1200)
@Composable
private fun LaptopLightPreview() = DiligentTheme(darkTheme = false) {
    LaptopContent(
        machines = listOf(previewMachine),
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample diligent@android",
        keyError = null,
        keyBusy = false,
        saveFailed = false,
        tests = mapOf("mac" to TestState.Done(LaptopTest.Connected("Darwin arm64", node = true, git = true))),
        onMakeKey = {}, onCopy = {}, onSave = { _, _ -> }, onRemove = {}, onTest = {},
    )
}

@Preview(name = "Laptop, dark, unreachable", widthDp = 360, heightDp = 1200)
@Composable
private fun LaptopDarkPreview() = DiligentTheme(darkTheme = true) {
    LaptopContent(
        machines = listOf(previewMachine),
        publicKey = null,
        keyError = null,
        keyBusy = false,
        saveFailed = false,
        tests = mapOf("mac" to TestState.Done(LaptopTest.Failed(SshFailure.Unreachable, listOf("ssh: connect to host 192.168.1.20 port 22: Operation timed out")))),
        onMakeKey = {}, onCopy = {}, onSave = { _, _ -> }, onRemove = {}, onTest = {},
    )
}
