package ru.hiddi.messenger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hiddi.messenger.security.CalculatorLockStore
import java.math.BigDecimal

@Composable
fun CalculatorLockScreen(store: CalculatorLockStore, onUnlocked: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("0") }
    fun press(key: String) {
        when (key) {
            "C" -> { input = ""; display = "0" }
            "⌫" -> { input = input.dropLast(1); display = input.ifBlank { "0" } }
            "=" -> {
                if (input.isNotEmpty() && input.all(Char::isDigit) && store.verify(input.toCharArray())) {
                    onUnlocked()
                } else {
                    val result = evaluateCalculatorExpression(input)
                    input = result.takeUnless { it == "0" }.orEmpty()
                    display = result
                }
            }
            else -> if (input.length < 12) { input += key; display = input }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Калькулятор", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(display, modifier = Modifier.padding(22.dp), fontSize = 34.sp, maxLines = 1)
            }
            listOf(listOf("7","8","9","⌫"), listOf("4","5","6","C"), listOf("1","2","3","+"), listOf("0",".","−","=")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key -> Surface(Modifier.weight(1f).size(66.dp).clickable { press(key) }, shape = RoundedCornerShape(18.dp), color = if (key == "=") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) { Text(key, Modifier.padding(top = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 21.sp) } }
                }
            }
        }
    }
}

internal fun evaluateCalculatorExpression(value: String): String {
    val expression = value.replace('−', '-')
    if (!expression.matches(Regex("""\d+(?:\.\d+)?(?:[+-]\d+(?:\.\d+)?)*"""))) return "0"
    val tokens = Regex("""([+-]?)(\d+(?:\.\d+)?)""").findAll(expression).toList()
    if (tokens.isEmpty()) return "0"
    val result =
        tokens.fold(BigDecimal.ZERO) { total, token ->
            val number = token.groupValues[2].toBigDecimal()
            if (token.groupValues[1] == "-") total - number else total + number
        }
    return result.stripTrailingZeros().toPlainString()
}
