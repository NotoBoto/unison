package io.github.notoboto.unison.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Choosing a character's reader colour (ROLE_COLORS_GROOM.md §6). [palette]/[bg]/[fg]: what the text uses right now, so the
 *  circles look exactly like the text will. [current]: the colour shown now (-1 = none yet); [pinned]: chosen by the user;
 *  [others]: colour -> other speakers of this chapter showing it (empty outside a chapter); [owners]: colour -> who holds it
 *  in the book (RoleColors.owners). [onPick]: a colour, or null = automatic. Both close the dialog. */
@Composable
fun RoleColorDialog(name: String, current: Int, pinned: Boolean, palette: List<Color>, bg: Color, fg: Color,
                    others: Map<Int, List<String>>, owners: Map<Int, List<String>>, pinnedCount: Int,
                    onPick: (Int?) -> Unit, onResetAll: () -> Unit, onDismiss: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    // who wears each colour: this chapter's speakers first, then the book's owners by chapters met in
    val label = remember(others, owners, palette.size) {
        List(palette.size) { k -> ((others[k] ?: emptyList()) + (owners[k] ?: emptyList())).distinct().take(2).joinToString() }
    }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Цвет: $name", maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(8.dp).selectableGroup()) {
                for (row in 0 until (palette.size + 1) / 2) Row(Modifier.fillMaxWidth()) {
                    for (k in row * 2 until minOf(row * 2 + 2, palette.size)) {
                        val chosen = pinned && k == current
                        val l = label[k]
                        Row(Modifier.weight(1f).heightIn(min = 48.dp)
                            .selectable(selected = chosen, role = Role.RadioButton, onClick = {
                                if (!chosen) onPick(k)
                                onDismiss()
                            })
                            .semantics {
                                contentDescription = roleColorNames.getOrElse(k) { "цвет ${k + 1}" } +
                                    (if (l.isNotEmpty()) ", у персонажей: $l" else ", свободен")
                                if (!pinned && k == current) stateDescription = "сейчас"
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(34.dp).then(if (k == current) Modifier.border(2.dp, fg, CircleShape) else Modifier),
                                    contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(palette[k]), contentAlignment = Alignment.Center) {
                                        if (chosen) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = bg)
                                    }
                                }
                                // a dot: someone else speaking in this chapter shows this colour
                                Box(Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape)
                                    .background(if (others[k].isNullOrEmpty()) Color.Transparent else fg))
                            }
                            Spacer(Modifier.width(6.dp))
                            // spoken through the cell's description, not twice
                            Text(l, Modifier.clearAndSetSemantics {}, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (label.any { it.isNotEmpty() }) Text("Рядом с цветом — кто его носит (точка — в этой главе). Если выбрать занятый цвет, " +
                "они возьмут другой во всей книге (кроме выбранных вручную).", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = sub, lineHeight = 15.sp)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 48.dp)
                .selectable(selected = !pinned, role = Role.RadioButton, onClick = { if (pinned) onPick(null); onDismiss() }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !pinned, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text("Автоматически")
            }
            if (pinnedCount > 0) TextButton(onClick = { confirmReset = true }) { Text("Сбросить ручные цвета книги ($pinnedCount)") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        text = { Text("Сбросить ручные цвета ($pinnedCount)? Персонажи вернутся к автоматическим.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onResetAll(); onDismiss() }) { Text("Сбросить") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } })
}
