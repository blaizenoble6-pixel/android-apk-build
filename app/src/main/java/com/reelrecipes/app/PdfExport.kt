package com.reelrecipes.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Makes a one-recipe PDF (A4) and opens the share sheet, so it can go by email, Messenger, Drive… */
object PdfExport {
    private const val W = 595; private const val H = 842; private const val M = 48

    suspend fun share(ctx: Context, store: RecipeStore, r: Recipe, servings: Int, cost: MealCost?) {
        val file = withContext(Dispatchers.IO) { build(ctx, store, r, servings, cost) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, r.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share recipe PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun build(ctx: Context, store: RecipeStore, r: Recipe, servings: Int, cost: MealCost?): File {
        val doc = PdfDocument()
        val green = Color.rgb(0x2E, 0x6A, 0x4E); val ink = Color.rgb(0x1D, 0x28, 0x20); val muted = Color.rgb(0x5D, 0x6B, 0x61)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
        val h2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = green; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = ink }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = muted }
        val mono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = green; typeface = Typeface.MONOSPACE }
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        var c = page.canvas
        var y = M.toFloat()

        fun newPage() { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.canvas; y = M.toFloat() }
        fun text(s: String, p: TextPaint, x: Float = M.toFloat(), width: Int = W - 2 * M, gap: Float = 4f) {
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
            if (y + l.height > H - M) newPage()
            c.save(); c.translate(x, y); l.draw(c); c.restore(); y += l.height + gap
        }

        // Cover photo
        r.photos.firstOrNull()?.let { store.photo(it) }?.takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.let { bmp ->
                val box = RectF(M.toFloat(), y, (W - M).toFloat(), y + 220f)
                val scale = maxOf(box.width() / bmp.width, box.height() / bmp.height)
                val sw = (box.width() / scale).toInt(); val sh = (box.height() / scale).toInt()
                val src = Rect((bmp.width - sw) / 2, (bmp.height - sh) / 2, (bmp.width + sw) / 2, (bmp.height + sh) / 2)
                c.drawBitmap(bmp, src, box, null); y += 232f
            }
        }
        text(r.title.ifBlank { "Recipe" } + if (r.variantName.isNotBlank()) " — ${r.variantName}" else "", title, gap = 6f)
        text(listOfNotNull(r.author.takeIf { it.isNotBlank() }, "Serves $servings", r.time.takeIf { it.isNotBlank() }).joinToString("   ·   "), small)
        cost?.takeIf { it.lines.any { l -> l.product != null } }?.let {
            text("Cost to make about ${Costing.money(it.total)} (${Costing.money(it.perServe)} a serve) at ${it.store}, priced ${java.time.LocalDate.now()}.", small)
        }
        y += 8f
        val f = servings.toDouble() / r.baseServings
        text("Ingredients", h2, gap = 6f)
        r.ingredients.forEach { ing ->
            val q = Qty.scale(ing.qty, f)
            val startY = y
            text(listOf(ing.item, ing.note).filter { it.isNotBlank() }.joinToString(", "), body, x = M + 90f, width = W - 2 * M - 90, gap = 3f)
            if (q.isNotBlank()) { val l = StaticLayout.Builder.obtain(q, 0, q.length, mono, 84).build(); c.save(); c.translate(M.toFloat(), if (y < startY) M.toFloat() else startY); l.draw(c); c.restore() }
        }
        y += 8f
        text("Method", h2, gap = 6f)
        r.steps.forEachIndexed { i, s -> text("${i + 1}.  $s", body, gap = 6f) }
        if (r.notes.isNotBlank()) { y += 6f; text("Notes", h2, gap = 6f); text(r.notes, body) }
        if (r.url.isNotBlank()) { y += 6f; text("Original: ${r.url}", small) }
        text("Made with Reel Recipes", small)
        doc.finishPage(page)
        val out = File(File(ctx.cacheDir, "pdf").apply { mkdirs() }, r.title.ifBlank { "recipe" }.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).trim().ifBlank { "recipe" } + ".pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
