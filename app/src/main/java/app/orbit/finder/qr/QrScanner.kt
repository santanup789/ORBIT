package app.orbit.finder.qr

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScanner(modifier: Modifier = Modifier, onCode: (String) -> Unit, onError: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    val lastCode = remember { java.util.concurrent.atomic.AtomicReference<String>(null) }
    val boundProvider = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider>(null) }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    AndroidView(modifier = modifier, factory = { context ->
        PreviewView(context).also { view ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ runCatching {
                val provider = future.get()
                if (disposed.get()) return@addListener
                boundProvider.set(provider)
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                var busy = false
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null || busy) { proxy.close(); return@setAnalyzer }
                    busy = true
                    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes -> codes.firstOrNull()?.rawValue?.let { if (lastCode.getAndSet(it) != it) onCode(it) } }
                        .addOnFailureListener { onError(it.message ?: "Camera scan failed") }
                        .addOnCompleteListener { busy = false; proxy.close() }
                }
                provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { onError(it.message ?: "Camera unavailable") } }, ContextCompat.getMainExecutor(context))
        }
    })
    DisposableEffect(Unit) { onDispose { disposed.set(true); boundProvider.get()?.unbindAll(); scanner.close(); executor.shutdown() } }
}
