package com.codexrm.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun MiuixSettingsScreen(
    title: String,
    darkTheme: () -> Boolean,
    sections: () -> List<SettingsPage.Section>,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    hasWallpaper: () -> Boolean = { false },
) {
    val dark = darkTheme()
    val groups = sections()
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        val background = if (hasWallpaper()) androidx.compose.ui.graphics.Color.Transparent else MiuixTheme.colorScheme.surface
        Column(Modifier.fillMaxSize().background(background)) {
            SmallTopAppBar(
                title = title,
                color = background,
                defaultWindowInsetsPadding = false,
                navigationIcon = {
                    MiuixActionIcon("back", "返回", onBack)
                },
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                groups.forEach { section ->
                    SmallTitle(section.title, modifier = Modifier.semantics { heading() })
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                        section.toggle?.let { toggle ->
                            SwitchPreference(
                                title = toggle.title,
                                summary = toggle.summary,
                                checked = toggle.checked(),
                                onCheckedChange = {
                                    toggle.change(it)
                                    onChanged()
                                },
                            )
                        }
                        section.actions.forEach { action ->
                            ArrowPreference(title = action.title, summary = action.summary?.invoke(), onClick = action.open)
                        }
                        section.choice?.let { choice ->
                            MiuixDropdownPreference(
                                title = choice.title,
                                items = choice.options,
                                selectedIndex = choice.selectedIndex(),
                                onSelectedIndexChange = {
                                    choice.select(it)
                                    onChanged()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
