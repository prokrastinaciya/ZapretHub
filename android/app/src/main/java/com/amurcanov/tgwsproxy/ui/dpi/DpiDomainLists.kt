package com.amurcanov.tgwsproxy.ui.dpi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import com.amurcanov.tgwsproxy.ui.BrandField
import com.amurcanov.tgwsproxy.ui.BrandMultilineField
import com.amurcanov.tgwsproxy.ui.BrandOutlineButton
import com.amurcanov.tgwsproxy.ui.MonoAction
import com.amurcanov.tgwsproxy.ui.MonoLabel
import com.amurcanov.tgwsproxy.ui.SectionLabel

/**
 * Domain list management (parity T-06): add, edit and delete named domain lists
 * referenced by `{list:name}` in the command line. Built-in asset lists are part
 * of the (not-yet-implemented) test tools and out of scope here.
 */
@Composable
internal fun DpiDomainLists() {
    val context = LocalContext.current
    val store = remember(context) { DpiSettingsStore(context) }
    val document = rememberDpiDocument(store)
    val lists = remember(document) { store.domainLists() }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var domains by remember { mutableStateOf("") }

    SectionLabel(stringResource(R.string.dpi_domain_lists_title))
    if (editing) {
        BrandField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.dpi_domain_list_name))
        BrandMultilineField(
            value = domains,
            onValueChange = { domains = it },
            placeholder = stringResource(R.string.dpi_domain_list_domains_hint),
        )
        BrandOutlineButton(stringResource(R.string.dpi_domain_list_save), onClick = {
            store.addDomainList(name, domains.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
            name = ""
            domains = ""
            editing = false
        }, enabled = name.isNotBlank() && domains.isNotBlank())
    } else {
        BrandOutlineButton(stringResource(R.string.dpi_domain_list_add), onClick = { editing = true })
    }

    if (lists.isEmpty()) {
        MonoLabel(stringResource(R.string.dpi_domain_lists_empty), Modifier.padding(vertical = 4.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lists.forEach { list ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = list.name, color = com.amurcanov.tgwsproxy.ui.Brand.ink)
                    MonoLabel(list.domains.joinToString(" "))
                }
                MonoAction(
                    text = stringResource(R.string.dpi_strategy_delete),
                    onClick = { store.deleteDomainList(list.id) },
                )
            }
        }
    }
}
