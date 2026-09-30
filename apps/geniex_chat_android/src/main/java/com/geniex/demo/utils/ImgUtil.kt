// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

class ImgUtil {
    companion object {
        /**
         * Resize imageFile so its shorter edge is [size], then centre-crop to a
         * square [size] x [size] and save to outFile (JPEG), returning outFile.
         *
         * The square is filled edge to edge with image content on purpose. An
         * earlier version centred the source on a blank canvas, which left black
         * letterbox bars on any non-square photo (a 448x355 input wasted rows
         * 0-46 and 402-447). Vision encoders tokenise the square as a fixed grid
         * — for Qwen3.5-VL, 14x14 = 196 tokens — so those bars burned ~21% of the
         * image tokens on padding and squeezed the subject into less than 80% of
         * the vertical resolution, which was enough to make the model misread it.
         *
         * Cropping trades the edges of a wide frame for full resolution on the
         * centre, which is the standard preprocessing for CLIP-style encoders.
         */
        fun squareCrop(
            imageFile: File,
            outFile: File,
            size: Int = 448,
            quality: Int = 90,
        ): File {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)

            val opts =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    // Downsample while decoding, but keep the shorter edge at or
                    // above `size` so the resize below never has to upscale.
                    inSampleSize =
                        run {
                            val shorter = minOf(bounds.outWidth, bounds.outHeight)
                            var s = 1
                            while (shorter / (s * 2) >= size) s *= 2
                            s
                        }
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            var bmp = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: error("decode fail")

            // Apply EXIF orientation after sampled decode so portrait photos do
            // not require a second full-resolution bitmap in memory.
            val exif = ExifInterface(imageFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) {
                    bmp.recycle()
                    bmp = rotated
                }
            }

            // Scale so the shorter edge lands exactly on `size`; the longer edge
            // overflows and is trimmed by the centre-crop below.
            val scale = size.toFloat() / minOf(bmp.width, bmp.height)
            val scaledW = ceil(bmp.width * scale).toInt().coerceAtLeast(size)
            val scaledH = ceil(bmp.height * scale).toInt().coerceAtLeast(size)
            val scaled =
                if (bmp.width != scaledW || bmp.height != scaledH) {
                    Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
                } else {
                    bmp
                }
            if (scaled !== bmp) bmp.recycle()

            val cropped =
                Bitmap.createBitmap(
                    scaled,
                    (scaled.width - size) / 2,
                    (scaled.height - size) / 2,
                    size,
                    size,
                )
            if (cropped !== scaled) scaled.recycle()

            FileOutputStream(outFile).use { fos ->
                cropped.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 95), fos)
            }
            if (!cropped.isRecycled) cropped.recycle()
            return outFile
        }
    }
}
