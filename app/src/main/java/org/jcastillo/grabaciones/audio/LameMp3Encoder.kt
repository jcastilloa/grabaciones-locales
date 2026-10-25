/*
 * Copyright (C) 2026 Grabaciones Locales contributors.
 * Licensed under GPL-3.0-or-later; LAME is a separate LGPL-2.0-or-later library.
 */
package org.jcastillo.grabaciones.audio

/** Local, synchronous JNI wrapper. Call only from an I/O thread. */
class LameMp3Encoder {
    external fun encodeWavToMp3(inputPath: String, outputPath: String, bitrateKbps: Int): Long

    companion object {
        init { System.loadLibrary("mp3bridge") }
    }
}
