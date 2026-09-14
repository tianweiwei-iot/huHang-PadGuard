package com.padguard.data.importer

import android.util.Xml
import com.padguard.domain.model.DeviceImportRow
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备台账导入文件解析器
 *
 * 支持两种主流表格格式（对标 MDM 批量注册的 CSV/XLSX 模板）：
 * - .csv：UTF-8（含 BOM）/ UTF-16 / GBK 自动识别，支持引号转义
 * - .xlsx：ECMA-376 标准 OOXML（sharedStrings + worksheet），使用平台自带 XmlPullParser 流式解析，
 *   不引入 Apache POI 等重型依赖；由 Excel / WPS 导出的常规表格均可解析
 *
 * 列约定（首行为表头，按"设备编号/别名/型号/分组"表头名匹配；无表头时按固定列序解析）：
 * 设备编号(必填) | 设备别名 | 型号 | 分组
 */
@Singleton
class DeviceImportFileParser @Inject constructor() {

    /** 表头关键字 -> 列序号（解析表头行时建立映射） */
    private enum class Column(vararg val names: String) {
        HARDWARE_ID("设备编号", "设备ID", "序列号", "SN"),
        ALIAS("设备别名", "别名", "名称"),
        MODEL("型号", "设备型号"),
        GROUP("分组", "设备分组", "班级")
    }

    fun parse(fileName: String, input: InputStream): Result<List<DeviceImportRow>> {
        val lowered = fileName.lowercase()
        return when {
            lowered.endsWith(".xlsx") -> parseXlsx(input).mapCatching { toRows(it) }
            lowered.endsWith(".xls") ->
                Result.failure(IllegalArgumentException("暂不支持旧版 .xls 格式，请在 Excel/WPS 中另存为 .xlsx 或 .csv"))
            lowered.endsWith(".csv") -> parseCsv(input).mapCatching { toRows(it) }
            else -> Result.failure(IllegalArgumentException("仅支持 .xlsx 或 .csv 文件"))
        }
    }

    // ==================== 行 -> 领域模型 ====================

    private fun toRows(grid: List<List<String>>): List<DeviceImportRow> {
        if (grid.isEmpty()) return emptyList()
        val header = grid.first().map { it.trim() }
        val headerIndex = resolveHeaderIndex(header)
        val dataRows = if (headerIndex != null) grid.drop(1) else grid

        return dataRows.mapNotNull { line ->
            val cells = line.map { it.trim() }
            val hardwareId = pick(cells, headerIndex, Column.HARDWARE_ID, 0)
            // 整行为空则跳过（表格尾部空行）
            if (cells.all { it.isEmpty() }) return@mapNotNull null
            DeviceImportRow(
                hardwareId = hardwareId,
                alias = pick(cells, headerIndex, Column.ALIAS, 1).ifEmpty { null },
                model = pick(cells, headerIndex, Column.MODEL, 2).ifEmpty { null },
                groupName = pick(cells, headerIndex, Column.GROUP, 3).ifEmpty { null }
            )
        }
    }

    /** 识别表头行：若首行包含已知表头关键字则返回列映射，否则返回 null（按固定列序） */
    private fun resolveHeaderIndex(header: List<String>): Map<Column, Int>? {
        val mapping = Column.entries.mapNotNull { col ->
            val idx = header.indexOfFirst { cell -> col.names.any { cell.contains(it) } }
            if (idx >= 0) col to idx else null
        }.toMap()
        return if (mapping.containsKey(Column.HARDWARE_ID)) mapping else null
    }

    private fun pick(
        cells: List<String>,
        headerIndex: Map<Column, Int>?,
        column: Column,
        fallbackIndex: Int
    ): String {
        val idx = headerIndex?.get(column) ?: fallbackIndex
        return cells.getOrNull(idx)?.trim().orEmpty()
    }

    // ==================== CSV ====================

    private fun parseCsv(input: InputStream): Result<List<List<String>>> = runCatching {
        val text = readTextWithCharsetDetection(input)
        CsvGridReader.parse(text)
    }

    /** 按字符逐格解析 CSV：支持引号包裹、内嵌逗号/换行、双引号转义 */
    private object CsvGridReader {
        fun parse(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            var cell = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    inQuotes -> when {
                        c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                        c == '"' -> inQuotes = false
                        else -> cell.append(c)
                    }
                    c == '"' -> inQuotes = true
                    c == ',' -> { row.add(cell.toString()); cell = StringBuilder() }
                    c == '\r' -> { /* 由 \n 统一处理 */ }
                    c == '\n' -> { row.add(cell.toString()); cell = StringBuilder(); rows.add(row); row = mutableListOf() }
                    else -> cell.append(c)
                }
                i++
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
            return rows
        }
    }

    /** BOM 优先；无 BOM 时先严格按 UTF-8 解码，失败则回退 GBK（中文 Excel 导出常见编码） */
    private fun readTextWithCharsetDetection(input: InputStream): String {
        val bytes = input.readBytes()
        val bomCharset = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            else -> null
        }
        if (bomCharset != null) return String(bytes, bomCharset)

        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("GBK"))
        }
    }

    // ==================== XLSX（ECMA-376 最小解析） ====================

    /**
     * 解析 xlsx 第一个工作表为字符串二维表。
     * xlsx 本质是 ZIP 包：xl/sharedStrings.xml 存共享字符串，xl/worksheets/sheetN.xml 存单元格。
     * 两者在包内的先后顺序由生成方决定（Excel 将 sharedStrings 放在 worksheet 之后），
     * 因此先整体读入条目缓存再分别解析。
     */
    private fun parseXlsx(input: InputStream): Result<List<List<String>>> = runCatching {
        val entries = readZipEntries(input)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetName = entries.keys
            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .minOrNull()
            ?: throw IllegalArgumentException("表格中未找到工作表")
        parseWorksheet(entries[sheetName]!!, sharedStrings)
    }

    private fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    result[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    /** 解析 sharedStrings.xml：<si> 内所有 <t> 文本拼接为一个共享字符串 */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = newParser(bytes)
        val strings = mutableListOf<String>()
        var current = StringBuilder()
        var inSi = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; current = StringBuilder() }
                    "t" -> if (inSi) current.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    inSi = false
                    strings.add(current.toString())
                }
            }
            parser.next()
        }
        return strings
    }

    /** 解析 worksheet：<row> 内 <c r="B3" t="s"><v>idx</v></c>，按 r 属性还原列位置 */
    private fun parseWorksheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val parser = newParser(bytes)
        val rows = mutableListOf<List<String>>()
        var rowCells: MutableList<Pair<Int, String>>? = null
        var cellType: String? = null
        var cellRef: String? = null
        var cellValue = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> rowCells = mutableListOf()
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        cellRef = parser.getAttributeValue(null, "r")
                        cellValue = StringBuilder()
                    }
                    "v" -> cellValue.append(parser.nextText())
                    "t" -> if (cellType == "inlineStr") cellValue.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "c" && rowCells != null) {
                    val text = when (cellType) {
                        "s" -> sharedStrings.getOrNull(cellValue.toString().toIntOrNull() ?: -1).orEmpty()
                        else -> cellValue.toString()
                    }
                    rowCells.add(columnIndex(cellRef) to text)
                } else if (parser.name == "row" && rowCells != null) {
                    rows.add(unwrapRow(rowCells))
                    rowCells = null
                }
            }
            parser.next()
        }
        return rows
    }

    private fun unwrapRow(cells: List<Pair<Int, String>>): List<String> {
        if (cells.isEmpty()) return emptyList()
        val width = cells.maxOf { it.first } + 1
        val array = Array(width) { "" }
        cells.forEach { (col, value) -> if (col in array.indices) array[col] = value }
        return array.toList()
    }

    /** 列字母转 0 起始索引："A" -> 0，"C" -> 2；解析失败返回 -1（忽略该单元格） */
    private fun columnIndex(cellRef: String?): Int {
        if (cellRef.isNullOrBlank()) return -1
        val letters = cellRef.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return -1
        return letters.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(java.io.ByteArrayInputStream(bytes), null)
        return parser
    }
}
