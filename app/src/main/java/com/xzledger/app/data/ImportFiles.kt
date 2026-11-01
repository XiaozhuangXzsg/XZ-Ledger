package com.xzledger.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Xml
import com.xzledger.app.util.Attachments
import com.xzledger.app.util.Money
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * 文件解析与导入落库的入口。
 * 支持：微信/支付宝/通用 CSV、.xlsx（多工作表）、文本型 PDF；扫描型 PDF 渲染为图片后可交给 AI 识别。
 * 不支持的文件类型会明确提示，绝不静默截断（超出限制时同样提示分批导入）。
 */
object ImportFiles {

    private const val MAX_FILE_BYTES = ImportService.MAX_FILE_BYTES
    private const val MAX_ROWS = ImportService.MAX_ROWS
    private const val MAX_PDF_PAGES = ImportService.MAX_PDF_PAGES

    // ---------------- 入口 ----------------

    fun parseFile(db: Db, ctx: Context, uri: Uri, ledgerId: String, displayName: String): ParseResult {
        val name = displayName.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件" }
        val lower = name.lowercase()
        val bytes = try {
            readBytes(ctx, uri)
        } catch (e: Exception) {
            return ParseResult("unknown", name, emptyList(), listOf("读取文件失败：" + (e.message ?: "未知错误")))
        }
        if (bytes == null) {
            return ParseResult("unknown", name, emptyList(), listOf("文件超过 " + (MAX_FILE_BYTES / 1024 / 1024) + " MB 上限，请拆分后分批导入（未做静默截断）"))
        }
        return when {
            lower.endsWith(".csv") || lower.endsWith(".txt") ->
                parseCsvText(db, ledgerId, name, decode(bytes), "")
            lower.endsWith(".xlsx") -> parseXlsxBytes(db, ledgerId, name, bytes)
            lower.endsWith(".xls") -> ParseResult(
                "xls", name, emptyList(),
                listOf("暂不支持旧版 .xls 二进制格式（需第三方库，本机不预置）。请在 Excel/WPS 中另存为 .xlsx 或 CSV 后再导入。")
            )
            lower.endsWith(".pdf") -> parsePdfBytes(db, ctx, uri, ledgerId, name, bytes)
            else -> {
                val r = parseCsvText(db, ledgerId, name, decode(bytes), "")
                if (r.rows.isNotEmpty()) r
                else ParseResult("unknown", name, emptyList(), listOf("无法识别的文件类型，请选择 CSV / XLSX / PDF 账单文件"))
            }
        }
    }

    private fun readBytes(ctx: Context, uri: Uri): ByteArray? {
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法打开文件")
        ins.use { stream ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_FILE_BYTES) return null
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.indexOf('\uFFFD') < 0) return utf8
        return try { String(bytes, Charset.forName("GBK")) } catch (e: Exception) { utf8 }
    }

    // ---------------- CSV ----------------

    private fun detectDelimiter(text: String): Char {
        val lines = text.split('\n').filter { it.isNotBlank() }.take(10)
        var comma = 0
        var tab = 0
        var semi = 0
        for (l in lines) {
            comma += l.count { it == ',' }
            tab += l.count { it == '\t' }
            semi += l.count { it == ';' }
        }
        return when {
            tab > comma && tab >= semi -> '\t'
            semi > comma && semi > tab -> ';'
            else -> ','
        }
    }

    /** 支持引号包裹、双引号转义的定界符解析。 */
    fun parseDelimited(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                    } else sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == delim -> { row.add(sb.toString()); sb.setLength(0) }
                c == '\r' -> { }
                c == '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList() }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }

    private fun detectKind(name: String, text: String): String {
        val head = text.take(4000)
        if (name.contains("微信") || head.contains("微信支付") || head.contains("微信账单")) return "wechat"
        if (name.contains("支付宝") || head.contains("支付宝") || head.contains("alipay") || head.contains("ALIPAY")) return "alipay"
        return "csv"
    }

    /** 渠道 -> 平台标识：CSV 与 xlsx 走同一套，保证同一笔账单跨格式也能互相判重。 */
    private fun platformOf(kind: String): String = when (kind) {
        "wechat" -> "微信支付"
        "alipay" -> "支付宝"
        "excel" -> "Excel"
        else -> "CSV"
    }

    /** Excel 账单也按渠道识别平台：微信导出的 xlsx 不再统一记成「Excel」，否则同一笔用 CSV 再导一次会被当成新记录。 */
    private fun xlsxPlatform(name: String, table: List<List<String>>): String {
        val sample = table.take(60).joinToString(" ") { row -> row.joinToString(" ") }
        val kind = detectKind(name, sample)
        return if (kind == "wechat" || kind == "alipay") platformOf(kind) else "Excel"
    }

    fun parseCsvText(db: Db, ledgerId: String, name: String, text: String, forcedKind: String): ParseResult {
        val problems = ArrayList<String>()
        val rows = ArrayList<ImportRow>()
        if (text.isBlank()) return ParseResult("csv", name, emptyList(), listOf("文件内容为空"))
        val table = parseDelimited(text, detectDelimiter(text))
        if (table.isEmpty()) return ParseResult("csv", name, emptyList(), listOf("未能解析出任何表格行"))
        val kind = if (forcedKind.isNotEmpty()) forcedKind else detectKind(name, text)
        val platform = platformOf(kind)
        var headerIndex = -1
        var hm: Map<String, Int>? = null
        for (i in table.indices) {
            val m = ImportService.mapHeader(table[i])
            if (m != null) { headerIndex = i; hm = m; break }
        }
        if (hm == null) {
            problems.add("未识别到账单表头（需要同时包含金额列与时间或收支方向列）。请确认导出的是微信/支付宝官方账单 CSV，且前几行说明文字未被删改。")
        } else {
            rowsFromTable(db, ledgerId, table, headerIndex, hm, platform, TxnSource.IMPORT_CSV, name + " ", problems, rows)
        }
        return ParseResult(kind, name, rows, problems, emptyList(), text.take(ImportService.MAX_TEXT_FOR_AI))
    }

    // ---------------- Excel ----------------

    fun parseXlsxBytes(db: Db, ledgerId: String, name: String, bytes: ByteArray): ParseResult {
        val problems = ArrayList<String>()
        val rows = ArrayList<ImportRow>()
        val sheets = try {
            Xlsx.sheets(bytes)
        } catch (e: Exception) {
            return ParseResult("xlsx", name, emptyList(), listOf("Excel 解析失败：" + (e.message ?: "文件损坏或格式不支持")))
        }
        if (sheets.isEmpty()) return ParseResult("xlsx", name, emptyList(), listOf("未在 Excel 中找到工作表"))
        for ((sheetName, table) in sheets) {
            if (table.isEmpty()) continue
            var headerIndex = -1
            var hm: Map<String, Int>? = null
            for (i in table.indices) {
                val m = ImportService.mapHeader(table[i])
                if (m != null) { headerIndex = i; hm = m; break }
            }
            if (hm == null) {
                problems.add("工作表「" + sheetName + "」未识别到账单表头，已跳过")
                continue
            }
            if (!rowsFromTable(db, ledgerId, table, headerIndex, hm, xlsxPlatform(name, table), TxnSource.IMPORT_XLSX, "工作表 " + sheetName + " ", problems, rows)) break
        }
        return ParseResult("xlsx", name, rows, problems)
    }

    // ---------------- 表格 -> 预览行 ----------------

    private fun rowsFromTable(
        db: Db,
        ledgerId: String,
        table: List<List<String>>,
        headerIndex: Int,
        hm: Map<String, Int>,
        platform: String,
        source: TxnSource,
        originPrefix: String,
        problems: MutableList<String>,
        rows: MutableList<ImportRow>
    ): Boolean {
        for (i in headerIndex + 1 until table.size) {
            if (rows.size >= MAX_ROWS) {
                problems.add("超过单次 " + MAX_ROWS + " 条记录上限，已停止解析（未静默截断），请拆分文件分批导入")
                return false
            }
            val cells = table[i]
            if (cells.all { it.isBlank() }) continue
            if (ImportService.mapHeader(cells) != null) continue
            val origin = originPrefix + "第 " + (i + 1) + " 行"
            val timeText = cell(cells, hm, "time")
            val date = ImportService.parseDate(timeText) ?: ImportService.parseDate(cells.joinToString(" "))
            val timePair = ImportService.parseTime(timeText)
            val dirText = cell(cells, hm, "direction")
            val typeText = cell(cells, hm, "type")
            var amountText = cell(cells, hm, "amount")
            var type = directionOf(dirText, typeText)
            val incomeAmt = cell(cells, hm, "incomeAmount")
            val expenseAmt = cell(cells, hm, "expenseAmount")
            if (amountText.isBlank()) {
                if (incomeAmt.isNotBlank()) { amountText = incomeAmt; if (type == null) type = TxnType.INCOME }
                else if (expenseAmt.isNotBlank()) { amountText = expenseAmt; if (type == null) type = TxnType.EXPENSE }
            }
            val amount = Money.parseLoose(amountText)
            val status = ImportService.statusOf(cell(cells, hm, "status"))
            val merchant = cell(cells, hm, "merchant")
            val product = cell(cells, hm, "product")
            val noteCell = cell(cells, hm, "note")
            val note = listOf(product, noteCell).filter { it.isNotBlank() }.joinToString(" / ")
            val tradeNo = DedupKeys.normNo(cell(cells, hm, "tradeNo"))
            val orderNo = DedupKeys.normNo(cell(cells, hm, "orderNo"))
            val accountName = cell(cells, hm, "account")
            val refundAmt = cell(cells, hm, "refund")
            var finalType = type ?: TxnType.EXPENSE
            if (refundAmt.isNotBlank() && (Money.parseLoose(refundAmt) ?: 0L) > 0L &&
                status != TxnStatus.REFUNDED && status != TxnStatus.PARTIAL_REFUND) {
                finalType = TxnType.REFUND
            }
            if (finalType == TxnType.EXPENSE && amount != null && amount < 0 &&
                (dirText.contains("退款") || status == TxnStatus.REFUNDED || status == TxnStatus.PARTIAL_REFUND)) {
                finalType = TxnType.REFUND
            }
            var extra = ""
            if (refundAmt.isNotBlank() && (Money.parseLoose(refundAmt) ?: 0L) > 0L) {
                extra = "该笔含退款信息：成功退款 " + refundAmt + " 元，请核对该退款是否已单独记录"
            }
            if (amount == null && amountText.isBlank()) extra = "该行未找到金额列内容"
            rows.add(
                ImportService.buildRow(
                    db, ledgerId, i, origin, amount, date ?: "", timePair, finalType, status,
                    merchant, note, tradeNo, orderNo, accountName, platform, source,
                    natureOverride = null, categoryOverrideId = null, extra = extra
                )
            )
        }
        return true
    }

    private fun cell(cells: List<String>, map: Map<String, Int>, field: String): String {
        val i = map[field] ?: return ""
        return if (i in cells.indices) cells[i].trim() else ""
    }

    private fun directionOf(dirText: String, typeText: String): TxnType? {
        val t = (dirText + " " + typeText).trim()
        if (t.isEmpty() || t == "/" || t == "-") return null
        return when {
            t.contains("退款") || t.contains("退货") -> TxnType.REFUND
            t.contains("支出") || t.contains("付款") || t.contains("转出") || t.contains("借") || t.contains("消费") -> TxnType.EXPENSE
            t.contains("收入") || t.contains("收款") || t.contains("转入") || t.contains("贷") -> TxnType.INCOME
            else -> null
        }
    }

    // ---------------- PDF ----------------

    fun parsePdfBytes(db: Db, ctx: Context, uri: Uri, ledgerId: String, name: String, bytes: ByteArray): ParseResult {
        val problems = ArrayList<String>()
        val rows = ArrayList<ImportRow>()
        val text = Pdf.text(bytes)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var headerIndex = -1
        var hm: Map<String, Int>? = null
        for (i in lines.indices) {
            val m = ImportService.mapHeader(splitCells(lines[i]))
            if (m != null) { headerIndex = i; hm = m; break }
        }
        if (hm != null) {
            val table = lines.map { splitCells(it) }
            rowsFromTable(db, ledgerId, table, headerIndex, hm, "PDF", TxnSource.IMPORT_PDF, "PDF " + name + " ", problems, rows)
        }
        val good = rows.count { it.state == ImportState.NEW.id || it.state == ImportState.SUSPECTED.id }
        if (good < 3) {
            // 退化为逐行正则提取，结果一律标记待确认
            val fallback = ArrayList<ImportRow>()
            parseLooseLines(db, ledgerId, lines, name, fallback)
            if (fallback.size > rows.size) {
                rows.clear()
                rows.addAll(fallback)
                problems.add("PDF 未识别到规整表格，已改为逐行提取，" + fallback.size + " 条结果全部标记为待确认，请逐笔核对后导入")
            }
        }
        var images = emptyList<String>()
        if (rows.count { it.state != ImportState.FAILED.id && it.state != ImportState.DUPLICATE.id } < 3) {
            images = try { Pdf.renderPages(ctx, uri, MAX_PDF_PAGES) } catch (e: Exception) { emptyList() }
            if (images.isNotEmpty()) {
                problems.add("该 PDF 未包含可稳定提取的表格文字（可能是扫描件 / 图片型 PDF），已渲染前 " + images.size + " 页图片。可点击「交给 AI 识别」使用支持图片输入的模型，或改用 CSV / Excel 导入。")
            } else if (rows.isEmpty()) {
                problems.add("该 PDF 未提取到文字，也无法渲染页面。请改用 CSV / Excel 导入或手动记账。")
            }
        }
        return ParseResult("pdf", name, rows, problems, images, text.take(ImportService.MAX_TEXT_FOR_AI))
    }

    private fun splitCells(line: String): List<String> =
        line.split(Regex("\\s{2,}|\\t|\\s*\\|\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseLooseLines(db: Db, ledgerId: String, lines: List<String>, name: String, out: MutableList<ImportRow>) {
        var i = 0
        for (line in lines) {
            if (out.size >= MAX_ROWS) break
            val date = ImportService.parseDate(line) ?: continue
            val numbers = Regex("(?:[¥￥]\\s*)?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*元?").findAll(line)
                .mapNotNull { Money.parseLoose(it.groupValues[1]) }
                .filter { it > 0L }
                .toList()
            val amount = numbers.maxOrNull() ?: continue
            val timePair = ImportService.parseTime(line)
            val merchant = line.replace(Regex("[0-9¥￥:：.,，\\-/年月日]"), " ").trim().split(Regex("\\s+"))
                .filter { it.length >= 2 }.take(2).joinToString(" ")
            val type = directionOf(line, "")
            val row = ImportService.buildRow(
                db, ledgerId, i, "PDF " + name + " 第 " + (i + 1) + " 行", amount, date, timePair,
                type ?: TxnType.EXPENSE, ImportService.statusOf(line), merchant, "", "", "",
                "", "PDF", TxnSource.IMPORT_PDF, null, null, "PDF 文字定位不精确，请核对"
            )
            out.add(if (row.state == ImportState.NEW.id) row.copy(state = ImportState.NEED_CONFIRM.id) else row)
            i++
        }
    }

    // ---------------- 落库 / 撤销 / 批量修改 ----------------

    fun commit(db: Db, ledgerId: String, fileName: String, source: String, rows: List<ImportRow>): ImportOutcome {
        val batchId = Ids.newId()
        var saved = 0
        var dup = 0
        var failed = 0
        val ids = ArrayList<String>()
        val msgs = ArrayList<String>()
        for (r in rows) {
            if (!r.selected) continue
            if (r.state == ImportState.DUPLICATE.id) { dup++; continue }
            if (r.state == ImportState.FAILED.id) { failed++; msgs.add(r.origin + "：" + r.message); continue }
            val t = r.txn.copy(importBatchId = batchId)
            val o = db.saveTxn(t)
            if (o.ok) {
                saved++
                ids.add(t.id)
                if (t.type == TxnType.REFUND && t.refundOf != null) markRefunded(db, t.refundOf, t.amount)
            } else if (o.duplicateOf != null) {
                dup++
                msgs.add(r.origin + "：与已有记录重复，已跳过")
            } else {
                failed++
                msgs.add(r.origin + "：保存失败 " + o.message)
            }
        }
        db.addImportBatch(
            ImportBatch(
                id = batchId, ledgerId = ledgerId, fileName = fileName, source = source,
                createdAt = System.currentTimeMillis(), createdTxnIds = ids, undone = false
            )
        )
        db.enqueue("import_batch", batchId, "create", 1)
        return ImportOutcome(batchId, saved, dup, failed, msgs)
    }

    private fun markRefunded(db: Db, originalId: String, refundAmount: Long) {
        val original = db.txnById(originalId) ?: return
        val total = db.refundedAmount(originalId) + refundAmount
        val status = if (total >= original.amount) TxnStatus.REFUNDED else TxnStatus.PARTIAL_REFUND
        db.updateTxn(original.copy(txStatus = status, updatedAt = System.currentTimeMillis()))
    }

    fun undoImport(db: Db, batchId: String): Int {
        val b = db.importBatch(batchId) ?: return 0
        if (b.undone) return 0
        val ids = b.createdTxnIds.filter { id -> val t = db.txnById(id); t != null && !t.deleted }
        db.softDeleteTxns(ids)
        db.markImportUndone(batchId)
        return ids.size
    }

    /** 批量修改分类 / 消费性质（只作用于选中的预览行，未确认前不写库）。 */
    fun applyToRows(
        rows: List<ImportRow>,
        keys: Set<String>,
        categoryId: String?,
        nature: Nature?
    ): List<ImportRow> = rows.map { r ->
        if (!keys.contains(r.rowKey())) r
        else {
            val newCategory = categoryId ?: r.txn.categoryId
            val newNature = if (r.txn.type == TxnType.EXPENSE) (nature ?: r.txn.nature) else null
            val state = if (r.state == ImportState.NEED_CONFIRM.id && newCategory != null) ImportState.NEW.id else r.state
            r.copy(
                txn = r.txn.copy(categoryId = newCategory, nature = newNature, classifyBasis = ClassifyBasis.MANUAL, updatedAt = System.currentTimeMillis()),
                state = state
            )
        }
    }
}

// ---------------- XLSX ----------------

internal object Xlsx {

    fun sheets(bytes: ByteArray): List<Pair<String, List<List<String>>>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val name = e.name
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    var total = 0
                    var truncated = false
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > 8 * 1024 * 1024) { truncated = true; break }
                        bos.write(buf, 0, n)
                    }
                    if (!truncated) entries[name] = bos.toByteArray()
                }
                zis.closeEntry()
            }
        }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val names = sheetNames(entries["xl/workbook.xml"])
        val rels = workbookRels(entries["xl/_rels/workbook.xml.rels"])
        val out = ArrayList<Pair<String, List<List<String>>>>()
        for (i in names.indices) {
            val rid = names[i].second
            val target = rels[rid]
            val path = when {
                target == null -> "xl/worksheets/sheet" + (i + 1) + ".xml"
                target.startsWith("/") -> target.substring(1)
                target.startsWith("xl/") -> target
                else -> "xl/" + target
            }
            val data = entries[path] ?: entries["xl/worksheets/sheet" + (i + 1) + ".xml"] ?: continue
            out.add(names[i].first to parseSheet(data, shared))
        }
        return out
    }

    private fun parseSharedStrings(data: ByteArray?): List<String> {
        if (data == null) return emptyList()
        val out = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        var inSi = false
        val sb = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "si") { inSi = true; sb.setLength(0) }
                XmlPullParser.TEXT -> if (inSi) sb.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "si") { inSi = false; out.add(sb.toString()) }
            }
            ev = p.next()
        }
        return out
    }

    private fun sheetNames(data: ByteArray?): List<Pair<String, String>> {
        if (data == null) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                val nm = p.getAttributeValue(null, "name") ?: ""
                val rid = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") ?: ""
                out.add(nm to rid)
            }
            ev = p.next()
        }
        return out
    }

    private fun workbookRels(data: ByteArray?): Map<String, String> {
        val out = HashMap<String, String>()
        if (data == null) return out
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id") ?: ""
                val target = p.getAttributeValue(null, "Target") ?: ""
                if (id.isNotEmpty() && target.isNotEmpty()) out[id] = target
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var v = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') v = v * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') v = v * 26 + (ch - 'a' + 1)
            else break
        }
        return v - 1
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        var row: MutableList<String>? = null
        var cellRef = ""
        var cellType = ""
        val text = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        cellRef = p.getAttributeValue(null, "r") ?: ""
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "c" -> {
                        val r = row
                        if (r != null) {
                            val idx = if (cellRef.isEmpty()) r.size else colIndex(cellRef)
                            val raw = text.toString().trim()
                            val value = if (cellType == "s") {
                                val si = raw.toIntOrNull()
                                if (si != null && si in shared.indices) shared[si] else raw
                            } else raw
                            if (idx >= 0) {
                                while (r.size <= idx) r.add("")
                                r[idx] = value
                            }
                        }
                        text.setLength(0)
                    }
                    "row" -> { row?.let { rows.add(it) }; row = null }
                }
            }
            ev = p.next()
        }
        return rows
    }
}

// ---------------- PDF ----------------

internal object Pdf {

    /** 尽力提取 PDF 文字；扫描件通常提取不到有效文字（由上层提示改用 AI / 手动）。 */
    fun text(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        val marker = "stream".toByteArray()
        val endMarker = "endstream".toByteArray()
        while (i < bytes.size) {
            val s = indexOf(bytes, marker, i)
            if (s < 0) break
            var start = s + 6
            if (start < bytes.size && bytes[start].toInt() == 13) start++
            if (start < bytes.size && bytes[start].toInt() == 10) start++
            val e = indexOf(bytes, endMarker, start)
            if (e < 0) break
            val raw = bytes.copyOfRange(start, e)
            val data = inflateOrRaw(raw)
            out.append(extractTextOps(data))
            i = e + 9
            if (out.length > 400000) break
        }
        return out.toString()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        var i = from
        outer@ while (i <= hay.size - needle.size) {
            var j = 0
            while (j < needle.size) {
                if (hay[i + j] != needle[j]) { i++; continue@outer }
                j++
            }
            return i
        }
        return -1
    }

    private fun inflateOrRaw(raw: ByteArray): ByteArray = try {
        val inf = Inflater()
        inf.setInput(raw)
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var guard = 0
        while (!inf.finished() && guard < 4096) {
            val n = inf.inflate(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
            guard++
        }
        inf.end()
        bos.toByteArray()
    } catch (e: Exception) {
        raw
    }

    private fun isHex(c: Char): Boolean = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

    private fun extractTextOps(data: ByteArray): String {
        val s = String(data, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' -> {
                    val inner = StringBuilder()
                    var j = i + 1
                    var depth = 1
                    while (j < s.length && depth > 0) {
                        val ch = s[j]
                        if (ch == '\\') {
                            val n = if (j + 1 < s.length) s[j + 1] else ' '
                            when (n) {
                                'n' -> inner.append('\n')
                                'r' -> inner.append('\r')
                                't' -> inner.append('\t')
                                '(', ')', '\\' -> inner.append(n)
                                else -> if (n in '0'..'7') {
                                    var oct = 0
                                    var k = j + 1
                                    var cnt = 0
                                    while (k < s.length && cnt < 3 && s[k] in '0'..'7') {
                                        oct = oct * 8 + (s[k] - '0'); k++; cnt++
                                    }
                                    inner.append(oct.toChar())
                                    j = k - 2
                                } else inner.append(n)
                            }
                            j += 2
                        } else if (ch == '(') {
                            depth++; inner.append(ch); j++
                        } else if (ch == ')') {
                            depth--
                            if (depth > 0) inner.append(ch)
                            j++
                        } else {
                            inner.append(ch); j++
                        }
                    }
                    sb.append(decodeLiteral(inner.toString()))
                    i = j
                }
                c == '<' && i + 1 < s.length && isHex(s[i + 1]) -> {
                    var j = i + 1
                    val hex = StringBuilder()
                    while (j < s.length && s[j] != '>') {
                        if (isHex(s[j])) hex.append(s[j])
                        j++
                    }
                    sb.append(decodeHex(hex.toString()))
                    i = j + 1
                }
                c == 'T' && i + 1 < s.length && (s[i + 1] == 'd' || s[i + 1] == 'D' || s[i + 1] == '*') -> { sb.append('\n'); i += 2 }
                c == 'T' && i + 1 < s.length && s[i + 1] == 'J' -> { sb.append(' '); i += 2 }
                else -> i++
            }
        }
        return sb.toString()
    }

    private fun decodeLiteral(s: String): String {
        if (s.length >= 2 && s[0] == '\u00FE' && s[1] == '\u00FF') {
            val b = ByteArray(s.length - 2)
            for (k in 2 until s.length) b[k - 2] = s[k].code.toByte()
            return String(b, Charsets.UTF_16BE)
        }
        return s
    }

    private fun decodeHex(hex: String): String {
        val n = hex.length / 2
        if (n == 0) return ""
        val b = ByteArray(n)
        for (k in 0 until n) {
            val hi = Character.digit(hex[k * 2], 16)
            val lo = Character.digit(hex[k * 2 + 1], 16)
            b[k] = (((if (hi < 0) 0 else hi) shl 4) or (if (lo < 0) 0 else lo)).toByte()
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return String(b, Charsets.ISO_8859_1)
    }

    /** 把 PDF 页面渲染为 PNG（用于交给支持图片输入的 AI 识别）。 */
    fun renderPages(ctx: Context, uri: Uri, maxPages: Int): List<String> {
        val out = ArrayList<String>()
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return out
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            val n = minOf(renderer.pageCount, maxPages)
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                try {
                    val w = if (page.width < 900) page.width * 2 else page.width
                    val h = (w.toFloat() * page.height / page.width).toInt()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val f = File(Attachments.dir(ctx), "pdfpage_" + System.currentTimeMillis() + "_" + i + ".png")
                    FileOutputStream(f).use { fo -> bmp.compress(Bitmap.CompressFormat.PNG, 85, fo) }
                    bmp.recycle()
                    out.add(f.absolutePath)
                } finally {
                    page.close()
                }
            }
        } finally {
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
        return out
    }
}
