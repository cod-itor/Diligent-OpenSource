package com.vscodroid.diligent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vscodroid.R
import com.vscodroid.diligent.ui.theme.DiligentTheme

/**
 * [text] as a QR code, square, at most [maxSize] wide, dark on light in both
 * themes. Drawn module by module, so it stays sharp at any density.
 */
@Composable
fun QrCode(text: String, description: String, maxSize: Dp, modifier: Modifier = Modifier) {
    val modules = remember(text) { encode(text) }
    val ink = DiligentTheme.colors.qrInk
    Canvas(
        modifier
            .widthIn(max = maxSize)
            .aspectRatio(1f)
            .background(DiligentTheme.colors.qrPaper, DiligentTheme.shapes.small)
            .padding(DiligentTheme.spacing.m)
            .semantics { contentDescription = description },
    ) {
        val count = modules.size
        if (count == 0) return@Canvas
        val cell = size.minDimension / count
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (modules[y][x]) {
                    // A hair over one cell, so no seam shows between neighbours.
                    drawRect(ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** The code's modules, row by row, without the quiet zone (the padding draws that). */
private fun encode(text: String): Array<BooleanArray> {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
}

@Preview(name = "QR code", widthDp = 280, heightDp = 280)
@Composable
private fun QrCodePreview() = DiligentTheme(darkTheme = true) {
    val url = "http://100.101.102.103:41234/?tkn=6f1c2e9a-1b2c-4d5e-8f90-a1b2c3d4e5f6"
    QrCode(url, stringResource(R.string.diligent_share_qr, url), DiligentTheme.sizes.qrCode)
}
