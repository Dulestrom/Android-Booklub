package io.github.dulestrom.booklub

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Reads a desktop screenshot of Settings, then Devices, with the phone's own QR decoder, so the
 * desktop's rendering is checked against the reader that has to scan it. Point
 * BOOKLUB_QR_PNG at a capture from `tools/shot.sh ... devices:pairing`.
 */
class QrTest {
    @Test
    fun theDesktopsQrCodeDecodesAsAnInvitation() {
        val path = System.getenv("BOOKLUB_QR_PNG")
        assumeTrue("BOOKLUB_QR_PNG is not set", path != null)
        val image = ImageIO.read(File(path!!))
        val pixels = IntArray(image.width * image.height).also { image.getRGB(0, 0, image.width, image.height, it, 0, image.width) }
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
        val text = reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        println("decoded: $text")
        assertTrue(text, text.startsWith("booklub:pair?v=1&id="))
    }
}
