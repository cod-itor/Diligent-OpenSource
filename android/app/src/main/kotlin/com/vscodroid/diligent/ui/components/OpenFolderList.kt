package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.vscodroid.R
import com.vscodroid.diligent.DeviceFolder
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * The three ways to open a folder that is not on the recent list: the system
 * picker (shared storage, SD cards), the device folders picked through it
 * before, and the folders already in the projects folder.
 *
 * [projectFolders] is null while that folder is being listed.
 */
@Composable
fun OpenFolderList(
    deviceFolders: List<DeviceFolder>,
    projectFolders: List<String>?,
    now: Long,
    onPicker: () -> Unit,
    onDeviceFolder: (DeviceFolder) -> Unit,
    onProjectFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            Text(
                stringResource(R.string.diligent_projects_open_title),
                modifier = Modifier.padding(horizontal = DiligentTheme.spacing.l, vertical = DiligentTheme.spacing.s),
                style = DiligentTheme.type.material.titleLarge,
                color = DiligentTheme.colors.onSurface,
            )
        }
        item {
            ListRow(
                title = stringResource(R.string.diligent_projects_open_device),
                subtitle = stringResource(R.string.diligent_projects_open_device_body),
                icon = Icons.Outlined.FolderOpen,
                onClick = onPicker,
            )
        }
        if (deviceFolders.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.diligent_projects_device_folders)) }
            items(deviceFolders, key = { "device:" + it.uri }) { folder ->
                ListRow(
                    title = folder.name,
                    subtitle = folder.lastOpened.takeIf { it > 0 }?.let {
                        stringResource(R.string.diligent_projects_last_opened, relativeTimeText(it, now))
                    },
                    icon = Icons.Outlined.PhoneAndroid,
                    onClick = { onDeviceFolder(folder) },
                )
            }
        }
        item { SectionHeader(stringResource(R.string.diligent_projects_in_projects_folder)) }
        when {
            projectFolders == null -> item {
                Box(Modifier.fillMaxWidth().padding(DiligentTheme.spacing.m), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            projectFolders.isEmpty() -> item {
                Text(
                    stringResource(R.string.diligent_projects_no_folders),
                    modifier = Modifier.padding(horizontal = DiligentTheme.spacing.m, vertical = DiligentTheme.spacing.s),
                    style = DiligentTheme.type.material.bodyMedium,
                    color = DiligentTheme.colors.onSurfaceVariant,
                )
            }
            else -> items(projectFolders, key = { "project:$it" }) { path ->
                ListRow(
                    title = path.substringAfterLast('/'),
                    icon = Icons.Outlined.Folder,
                    onClick = { onProjectFolder(path) },
                )
            }
        }
        item { Box(Modifier.padding(bottom = DiligentTheme.spacing.l)) }
    }
}

@Composable
private fun ListSample(projectFolders: List<String>?) {
    Surface(color = DiligentTheme.colors.scheme.surfaceContainerLow) {
        OpenFolderList(
            deviceFolders = listOf(DeviceFolder("content://x/notes", "notes", 1_800_000_000_000L - 7_200_000L)),
            projectFolders = projectFolders,
            now = 1_800_000_000_000L,
            onPicker = {}, onDeviceFolder = {}, onProjectFolder = {},
            modifier = Modifier.padding(top = DiligentTheme.spacing.m),
        )
    }
}

@Preview(name = "Open folder, light", widthDp = 360)
@Composable
private fun OpenFolderLightPreview() = DiligentTheme(darkTheme = false) {
    ListSample(listOf("/home/projects/shop", "/home/projects/weather-api"))
}

@Preview(name = "Open folder, empty projects folder, dark", widthDp = 360)
@Composable
private fun OpenFolderDarkPreview() = DiligentTheme(darkTheme = true) { ListSample(emptyList()) }
