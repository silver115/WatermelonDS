package me.magnum.melonds.pokewalker

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads daily step totals from step exports:
 *  - Samsung Health "Download personal data" (the .zip, or the
 *    step_daily_trend / pedometer_day_summary CSV inside it)
 *  - any CSV with a date column and a steps column (YYYY-MM-DD dates)
 *
 * Returns phone steps per local epoch day. Phones and watches both count
 * steps, so device series are never added together: Samsung's merged
 * "all devices" series is used when present, otherwise the busiest device.
 */
object StepFileImporter {

    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    fun read(context: Context, uri: Uri): Map<Long, Long> {
        val input = context.contentResolver.openInputStream(uri) ?: return emptyMap()
        BufferedInputStream(input).use { stream ->
            stream.mark(4)
            val magic = ByteArray(4)
            val n = stream.read(magic)
            stream.reset()
            return if (n == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                readZip(stream)
            } else {
                parseCsv(readText(stream))
            }
        }
    }

    private fun readZip(stream: InputStream): Map<Long, Long> {
        val texts = mutableListOf<String>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.lowercase()
                if (!entry.isDirectory && name.endsWith(".csv") &&
                    (name.contains("step_daily_trend") || name.contains("pedometer_day_summary"))
                ) {
                    texts += readText(zip)
                }
                zip.closeEntry()
            }
        }
        // Prefer the daily trend files (they carry the merged series); fall back to summaries.
        val result = HashMap<Long, Long>()
        texts.sortedByDescending { it.lineSequence().firstOrNull().orEmpty().contains("step_daily_trend") }
            .forEach { text ->
                parseCsv(text).forEach { (day, steps) -> if (!result.containsKey(day)) result[day] = steps }
            }
        return result
    }

    private fun readText(stream: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8").removePrefix("﻿")
    }

    fun parseCsv(text: String): Map<Long, Long> {
        val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val separator = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','

        var headerIndex = 0
        var header = split(lines[0], separator).map { it.trim().lowercase() }
        if (findColumn(header, "day_time") == null && findColumn(header, "date", "day") == null && lines.size > 1) {
            headerIndex = 1 // Samsung CSVs start with a metadata line
            header = split(lines[1], separator).map { it.trim().lowercase() }
        }
        val rows = lines.drop(headerIndex + 1).map { split(it, separator) }

        val samsungDay = findColumn(header, "day_time")
        return if (samsungDay != null) parseSamsung(header, rows, samsungDay) else parseGeneric(header, rows)
    }

    private fun parseSamsung(header: List<String>, rows: List<List<String>>, dayCol: Int): Map<Long, Long> {
        val countCol = findColumn(header, "count", "step_count") ?: return emptyMap()
        val sourceCol = findColumn(header, "source_type")
        val deviceCol = findColumn(header, "deviceuuid")

        val merged = HashMap<Long, Long>()
        val perDevice = HashMap<Pair<Long, String>, Long>()
        for (row in rows) {
            val millis = row.getOrNull(dayCol)?.trim()?.toLongOrNull() ?: continue
            val steps = row.getOrNull(countCol)?.trim()?.toDoubleOrNull()?.toLong() ?: continue
            val day = Math.floorDiv(millis, LocalDays.DAY_MS) // Samsung stores the local date as UTC midnight
            val source = sourceCol?.let { row.getOrNull(it)?.trim() }.orEmpty()
            if (source == "-2") {
                merged[day] = maxOf(merged[day] ?: 0L, steps)
            } else {
                val device = deviceCol?.let { row.getOrNull(it)?.trim() } ?: source
                val key = day to device
                perDevice[key] = (perDevice[key] ?: 0L) + steps
            }
        }
        val result = HashMap<Long, Long>()
        perDevice.forEach { (key, steps) -> result[key.first] = maxOf(result[key.first] ?: 0L, steps) }
        result.putAll(merged)
        return result
    }

    private fun parseGeneric(header: List<String>, rows: List<List<String>>): Map<Long, Long> {
        val dateCol = findColumn(header, "date", "day", "start_time", "timestamp") ?: 0
        val stepsCol = findColumn(header, "steps", "step_count", "step count", "count", "value") ?: 1
        val result = HashMap<Long, Long>()
        for (row in rows) {
            val day = parseDate(row.getOrNull(dateCol)?.trim().orEmpty()) ?: continue
            val steps = row.getOrNull(stepsCol)?.trim()?.toDoubleOrNull()?.toLong() ?: continue
            result[day] = (result[day] ?: 0L) + steps
        }
        return result
    }

    /** Accepts YYYY-MM-DD (optionally followed by a time) or YYYY/MM/DD. */
    private fun parseDate(value: String): Long? {
        val match = Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})""").find(value) ?: return null
        val (y, m, d) = match.destructured
        val month = m.toInt()
        val dayOfMonth = d.toInt()
        if (month !in 1..12 || dayOfMonth !in 1..31) return null
        return LocalDays.fromCivil(y.toInt(), month, dayOfMonth)
    }

    /** Exact column names win over prefixed ones (".count") so "count" never grabs "walk_step_count". */
    private fun findColumn(header: List<String>, vararg names: String): Int? {
        for (name in names) {
            val exact = header.indexOf(name)
            if (exact >= 0) return exact
        }
        for (name in names) {
            val prefixed = header.indexOfFirst { it.endsWith(".$name") }
            if (prefixed >= 0) return prefixed
        }
        return null
    }

    /** Minimal CSV field splitter that understands double-quoted fields. */
    private fun split(line: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == separator -> { out += field.toString(); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        out += field.toString()
        return out
    }
}
