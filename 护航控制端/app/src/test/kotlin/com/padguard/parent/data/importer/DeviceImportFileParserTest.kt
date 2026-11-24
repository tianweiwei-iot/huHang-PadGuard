package com.padguard.parent.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 导入解析防 OOM 上限的回归测试。
 *
 * 历史缺陷：[DeviceImportFileParser.readZipEntries] 原先用 entry.size 预判三条上限，
 * 但**流式生成的 ZIP（无中央目录，local header 的 size 字段为 0）读出来 entry.size == -1**，
 * `size > MAX` / `total + size > MAX` 全部判 false，恶意构造的压缩包可以无限耗内存；
 * 同理 CSV 路径的 readBytes() 是无界读取。
 * 修复后所有路径按**实际读取字节数**强制上限。这里把上限行为钉成可执行规则
 * （注入小上限值，避免测试真的构造 32MB 数据）。
 */
class DeviceImportFileParserTest {

    private fun parserWithLimits(
        entry: Long = 32 * 1024 * 1024L,
        entries: Int = 4096,
        total: Long = 128 * 1024 * 1024L,
        csv: Long = 32 * 1024 * 1024L
    ) = DeviceImportFileParser().apply {
        maxEntryBytes = entry
        maxEntries = entries
        maxTotalBytes = total
        maxCsvBytes = csv
    }

    /** 构造一个真实结构的最小 xlsx（sharedStrings + sheet1） */
    private fun xlsxBytes(
        sharedStrings: String = "<?xml version=\"1.0\"?><sst><si><t>PAD001</t></si><si><t>小明</t></si></sst>",
        sheetXml: String = """
            <?xml version="1.0"?>
            <worksheet><sheetData>
            <row><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(sharedStrings.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `正常 xlsx 解析出共享字符串行`() {
        val rows = parserWithLimits().parse("台账.xlsx", ByteArrayInputStream(xlsxBytes()))
        assertTrue("解析失败: ${rows.exceptionOrNull()}", rows.isSuccess)
        assertEquals(1, rows.getOrThrow().size)
        assertEquals("PAD001", rows.getOrThrow().first().hardwareId)
    }

    @Test
    fun `正常 CSV 带表头解析`() {
        val csv = "设备编号,设备别名,型号,分组\nPAD001,小明,AGS6,一班\n"
        val rows = parserWithLimits().parse("台账.csv", ByteArrayInputStream(csv.toByteArray()))
        assertTrue(rows.isSuccess)
        val row = rows.getOrThrow().first()
        assertEquals("PAD001", row.hardwareId)
        assertEquals("小明", row.alias)
    }

    @Test
    fun `ZIP 单条目按实际字节数超限即拒绝`() {
        // 注入 10 字节条目上限，实际条目（sharedStrings xml）远大于它
        val p = parserWithLimits(entry = 10)
        val result = p.parse("台账.xlsx", ByteArrayInputStream(xlsxBytes()))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `CSV 全文超限即拒绝 不再无界读取`() {
        val p = parserWithLimits(csv = 8)
        val result = p.parse("台账.csv", ByteArrayInputStream("设备编号,别名\nPAD001,小明\n".toByteArray()))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `ZIP 总字节数超限即拒绝`() {
        // 总上限压到低于两个条目之和，但高于单条目（单条目检查不拦，总和检查必须拦）
        val bytes = xlsxBytes()
        val single = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var n = 0L
            zip.nextEntry
            val buf = ByteArray(4096)
            while (true) {
                val r = zip.read(buf)
                if (r < 0) break
                n += r
            }
            n
        }
        val p = parserWithLimits(entry = single + 1, total = single) // 单条目放行、总和必拦
        val result = p.parse("台账.xlsx", ByteArrayInputStream(bytes))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `ZIP 条目数超限即拒绝`() {
        // 多塞一个无害小条目，把条目数上限压到 1：第二个条目必须被拦
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("<sst></sst>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("<worksheet/>".toByteArray())
            zip.closeEntry()
        }
        val p = parserWithLimits(entries = 1)
        val result = p.parse("台账.xlsx", ByteArrayInputStream(out.toByteArray()))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `损坏输入失败但不崩溃 统一转 Result failure`() {
        // 非 ZIP 非 CSV 内容 + xlsx 后缀：解包必然抛，必须收敛成 failure 而非异常逃逸
        val result = parserWithLimits().parse("台账.xlsx", ByteArrayInputStream("这不是zip".toByteArray()))
        assertTrue(result.isFailure)
    }
}
