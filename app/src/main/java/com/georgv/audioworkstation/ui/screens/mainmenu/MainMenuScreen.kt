package com.georgv.audioworkstation.ui.screens.mainmenu

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.tiles.MainTile
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.LanguageSwitcher


@Composable
fun MainMenuScreen(
    onQuickRecord: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenDevices: () -> Unit
) {
    ScreenScaffold(title = stringResource(R.string.app_title),
        actions = {
            LanguageSwitcher()
        }) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Bg)
                .padding(padding)
                .padding(12.dp)
        ) {

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                // GRID (занимает всё доступное место)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MainTile(
                            title = stringResource(R.string.menu_project),
                            subtitle = stringResource(R.string.menu_project_sub),
                            icon = Icons.Filled.FolderOpen,
                            accent = AppColors.Green,
                            onClick = onOpenProject,
                            modifier = Modifier.weight(1f)
                        )

                        MainTile(
                            title = stringResource(R.string.menu_library),
                            subtitle = stringResource(R.string.menu_library_sub),
                            icon = Icons.Filled.Headphones,
                            accent = AppColors.Yellow,
                            onClick = onOpenLibrary,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MainTile(
                            title = stringResource(R.string.menu_community),
                            subtitle = stringResource(R.string.menu_community_sub),
                            icon = Icons.Filled.Groups,
                            accent = AppColors.Pink,
                            onClick = onOpenCommunity,
                            modifier = Modifier.weight(1f)
                        )

                        MainTile(
                            title = stringResource(R.string.menu_devices),
                            subtitle = stringResource(R.string.menu_devices_sub),
                            icon = Icons.Filled.Headphones,
                            accent = AppColors.Cyan,
                            onClick = onOpenDevices,
                            modifier = Modifier.weight(1f)
                        )


                    }
                }

                // QUICK RECORD ВНИЗУ
                MainTile(
                    title = stringResource(R.string.menu_quick_record),
                    subtitle = stringResource(R.string.menu_quick_record_sub),
                    icon = Icons.Filled.Bolt,
                    accent = AppColors.Red,
                    onClick = onQuickRecord,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.3f)
                )
            }
        }
    }
}








