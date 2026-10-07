/*
 * Copyright (C) 2026 RideFlux project contributors.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of RideFlux. It is licensed under the GNU General
 * Public License v3.0-or-later; see the LICENSE file for the full text.
 */

package com.rideflux.protocol.familyv

/**
 * Family V **vendor model registry** — the NOSFET / LeaperKim model table as the vendor apps
 * carry it, plus a derived single-cell state-of-charge curve.
 *
 * ## What this is
 *
 * A transcription of two vendor tables:
 *
 * * the **live** NOSFET endpoint `http://www.baat22.com:800/nfapp/appconfig.txt` (version 11),
 *   serving `APEX` and `AERO`;
 * * the **embedded** LeaperKim table `Util.CAR_DATA_JSON` (version 9) in
 *   `com.laoniao.leaperkim` 1.4.8, serving the seven legacy models.
 *
 * Both are read through `tools/veteran_vendor_codec.py`'s `load_soc_tables_from_appconfig`
 * path, and the wire format they feed is documented in `findings/NOSFET_LEAPERKIM_VENDOR_APP.md`.
 *
 * ## The model key is FOUR characters, and that is a trap
 *
 * The wire carries a six-digit zero-padded version number. The vendor splits it `[0:3] + [3:4]`
 * for hardware and `[4:6]` for software, so the **hardware code is four characters**:
 * `"5010"` for APEX and `"5020"` for AERO — exactly the table keys.
 *
 * A decoder that keys on three characters never matches a curve and silently falls back to a
 * generic one. That is the failure this registry exists to prevent, and it is why [lookup] takes
 * the vendor's own code rather than a parsed integer.
 *
 * ## Every model shares one single-cell curve
 *
 * Measured across all twelve entries in both tables:
 *
 * | quantity | value |
 * |---|---|
 * | `SysVol / 4.20` | **an exact integer for every model** — the series cell count |
 * | per-cell voltage at 0 % SOC | **3.1500 V** (`315` centivolts) |
 * | per-cell voltage at 100 % SOC | **4.1250 V** (`412` centivolts) |
 * | max point-by-point deviation between any two models' per-cell curves | **0.000167 V** |
 *
 * [exactCurves] holds **all twelve models' own `volToSoc` arrays verbatim**, lifted by
 * `python3 tools/veteran_vendor_codec.py --dump-curves`. [curveCentiVolts] returns those, so a
 * Family-V percentage is the vendor's own figure and not an approximation.
 *
 * [perCellCurveCentiVolts] is kept alongside them because it explains the family and serves as
 * the baseline for uncatalogued models when the rider supplies the battery series cell count.
 */
object VeteranModelRegistry {

    /** Where a model's record came from. Both are vendor-static. */
    enum class Source {
        /** The live NOSFET endpoint, version 11. */
        NOSFET_LIVE,

        /** The embedded LeaperKim table, version 9. */
        LEAPERKIM_EMBEDDED,
    }

    /**
     * One model's vendor record plus its derived cell count.
     *
     * @param hardwareCode the vendor's **four-character** hardwareVersion key.
     * @param systemVoltageVolts `SysVol` as the vendor writes it, parsed. Equals
     *   `cells * 4.20` exactly.
     * @param cells the series cell count, derived as `round(SysVol / 4.20)`. Derived, not
     *   declared by the vendor.
     * @param continuousSoftHardSet the vendor's per-model behaviour flag.
     * @param isFirmwareSentinel true for the `6660`/`6661` firmware-update entries, which are
     *   **not vehicles**. A caller must not offer them as selectable models.
     */
    data class VeteranModel(
        val name: String,
        val hardwareCode: String,
        val sNum: Int,
        val systemVoltageVolts: Double,
        val cells: Int,
        val continuousSoftHardSet: Boolean,
        val source: Source,
        val isFirmwareSentinel: Boolean,
    ) {
        val seriesCells: Int get() = cells
    }

    /** The nominal full-charge voltage per cell that `SysVol` encodes. */
    const val NOMINAL_FULL_CELL_VOLTS: Double = 4.20

    /** Per-cell voltage at 0 % state of charge, identical for every real model. */
    const val PER_CELL_EMPTY_VOLTS: Double = 3.1500

    /** Per-cell voltage at 100 % state of charge, identical for every real model. */
    const val PER_CELL_FULL_VOLTS: Double = 4.1250

    /**
     * Each model's **exact** state-of-charge curve, as the vendor serves it: 100 points in
     * **centivolts for the whole pack**, index 0 = 0 % and index 99 = 100 %.
     */
    internal val exactCurves: Map<String, IntArray> = mapOf(
        // APEX — 151.2V, live v11
        "5010" to intArrayOf(
            11340, 11394, 11452, 11509, 11567, 11624, 11682, 11736, 11794, 11851,
            11909, 11966, 12024, 12056, 12092, 12128, 12164, 12200, 12236, 12272,
            12308, 12344, 12380, 12416, 12452, 12481, 12514, 12546, 12578, 12611,
            12643, 12676, 12708, 12740, 12773, 12805, 12838, 12870, 12899, 12928,
            12960, 12989, 13018, 13050, 13079, 13108, 13140, 13169, 13198, 13230,
            13255, 13280, 13306, 13334, 13360, 13385, 13414, 13439, 13464, 13493,
            13518, 13543, 13572, 13608, 13644, 13680, 13716, 13752, 13788, 13824,
            13860, 13896, 13932, 13968, 14004, 14044, 14083, 14123, 14166, 14206,
            14245, 14285, 14328, 14368, 14407, 14447, 14490, 14515, 14544, 14573,
            14598, 14627, 14656, 14681, 14710, 14738, 14764, 14792, 14821, 14850,
        ),
        // AERO — 126V, live v11
        "5020" to intArrayOf(
            9450, 9495, 9543, 9591, 9639, 9687, 9735, 9780, 9828, 9876,
            9924, 9972, 10020, 10047, 10077, 10107, 10137, 10167, 10197, 10227,
            10257, 10287, 10317, 10347, 10377, 10401, 10428, 10455, 10482, 10509,
            10536, 10563, 10590, 10617, 10644, 10671, 10698, 10725, 10749, 10773,
            10800, 10824, 10848, 10875, 10899, 10923, 10950, 10974, 10998, 11025,
            11046, 11067, 11088, 11112, 11133, 11154, 11178, 11199, 11220, 11244,
            11265, 11286, 11310, 11340, 11370, 11400, 11430, 11460, 11490, 11520,
            11550, 11580, 11610, 11640, 11670, 11703, 11736, 11769, 11805, 11838,
            11871, 11904, 11940, 11973, 12006, 12039, 12075, 12096, 12120, 12144,
            12165, 12189, 12213, 12234, 12258, 12282, 12303, 12327, 12351, 12375,
        ),
        // Patton-S — 126V, LeaperKim embedded v9
        "0070" to intArrayOf(
            9450, 9495, 9543, 9591, 9639, 9687, 9735, 9780, 9828, 9876,
            9924, 9972, 10020, 10047, 10077, 10107, 10137, 10167, 10197, 10227,
            10257, 10287, 10317, 10347, 10377, 10401, 10428, 10455, 10482, 10509,
            10536, 10563, 10590, 10617, 10644, 10671, 10698, 10725, 10749, 10773,
            10800, 10824, 10848, 10875, 10899, 10923, 10950, 10974, 10998, 11025,
            11046, 11067, 11088, 11112, 11133, 11154, 11178, 11199, 11220, 11244,
            11265, 11286, 11310, 11340, 11370, 11400, 11430, 11460, 11490, 11520,
            11550, 11580, 11610, 11640, 11670, 11703, 11736, 11769, 11805, 11838,
            11871, 11904, 11940, 11973, 12006, 12039, 12075, 12096, 12120, 12144,
            12165, 12189, 12213, 12234, 12258, 12282, 12303, 12327, 12351, 12375,
        ),
        // Sherman-L — 151.2V, LeaperKim embedded v9
        "0060" to intArrayOf(
            11340, 11394, 11452, 11509, 11567, 11624, 11682, 11736, 11794, 11851,
            11909, 11966, 12024, 12056, 12092, 12128, 12164, 12200, 12236, 12272,
            12308, 12344, 12380, 12416, 12452, 12481, 12514, 12546, 12578, 12611,
            12643, 12676, 12708, 12740, 12773, 12805, 12838, 12870, 12899, 12928,
            12960, 12989, 13018, 13050, 13079, 13108, 13140, 13169, 13198, 13230,
            13255, 13280, 13306, 13334, 13360, 13385, 13414, 13439, 13464, 13493,
            13518, 13543, 13572, 13608, 13644, 13680, 13716, 13752, 13788, 13824,
            13860, 13896, 13932, 13968, 14004, 14044, 14083, 14123, 14166, 14206,
            14245, 14285, 14328, 14368, 14407, 14447, 14490, 14515, 14544, 14573,
            14598, 14627, 14656, 14681, 14710, 14738, 14764, 14792, 14821, 14850,
        ),
        // LYNX — 151.2V, LeaperKim embedded v9
        "0050" to intArrayOf(
            11340, 11394, 11452, 11509, 11567, 11624, 11682, 11736, 11794, 11851,
            11909, 11966, 12024, 12056, 12092, 12128, 12164, 12200, 12236, 12272,
            12308, 12344, 12380, 12416, 12452, 12481, 12514, 12546, 12578, 12611,
            12643, 12676, 12708, 12740, 12773, 12805, 12838, 12870, 12899, 12928,
            12960, 12989, 13018, 13050, 13079, 13108, 13140, 13169, 13198, 13230,
            13255, 13280, 13306, 13334, 13360, 13385, 13414, 13439, 13464, 13493,
            13518, 13543, 13572, 13608, 13644, 13680, 13716, 13752, 13788, 13824,
            13860, 13896, 13932, 13968, 14004, 14044, 14083, 14123, 14166, 14206,
            14245, 14285, 14328, 14368, 14407, 14447, 14490, 14515, 14544, 14573,
            14598, 14627, 14656, 14681, 14710, 14738, 14764, 14792, 14821, 14850,
        ),
        // Patton — 126V, LeaperKim embedded v9
        "0040" to intArrayOf(
            9450, 9495, 9543, 9591, 9639, 9687, 9735, 9780, 9828, 9876,
            9924, 9972, 10020, 10047, 10077, 10107, 10137, 10167, 10197, 10227,
            10257, 10287, 10317, 10347, 10377, 10401, 10428, 10455, 10482, 10509,
            10536, 10563, 10590, 10617, 10644, 10671, 10698, 10725, 10749, 10773,
            10800, 10824, 10848, 10875, 10899, 10923, 10950, 10974, 10998, 11025,
            11046, 11067, 11088, 11112, 11133, 11154, 11178, 11199, 11220, 11244,
            11265, 11286, 11310, 11340, 11370, 11400, 11430, 11460, 11490, 11520,
            11550, 11580, 11610, 11640, 11670, 11703, 11736, 11769, 11805, 11838,
            11871, 11904, 11940, 11973, 12006, 12039, 12075, 12096, 12120, 12144,
            12165, 12189, 12213, 12234, 12258, 12282, 12303, 12327, 12351, 12375,
        ),
        // Sherman-s — 100.8V, LeaperKim embedded v9
        "0030" to intArrayOf(
            7560, 7596, 7634, 7673, 7711, 7750, 7788, 7824, 7862, 7901,
            7939, 7978, 8016, 8038, 8062, 8086, 8110, 8134, 8158, 8182,
            8206, 8230, 8254, 8278, 8302, 8321, 8342, 8364, 8386, 8407,
            8429, 8450, 8472, 8494, 8515, 8537, 8558, 8580, 8599, 8618,
            8640, 8659, 8678, 8700, 8719, 8738, 8760, 8779, 8798, 8820,
            8837, 8854, 8870, 8890, 8906, 8923, 8942, 8959, 8976, 8995,
            9012, 9029, 9048, 9072, 9096, 9120, 9144, 9168, 9192, 9216,
            9240, 9264, 9288, 9312, 9336, 9362, 9389, 9415, 9444, 9470,
            9497, 9523, 9552, 9578, 9605, 9631, 9660, 9677, 9696, 9715,
            9732, 9751, 9770, 9787, 9806, 9826, 9842, 9862, 9881, 9900,
        ),
        // ShermanMax — 100.8V, LeaperKim embedded v9
        "0011" to intArrayOf(
            7560, 7596, 7634, 7673, 7711, 7750, 7788, 7824, 7862, 7901,
            7939, 7978, 8016, 8038, 8062, 8086, 8110, 8134, 8158, 8182,
            8206, 8230, 8254, 8278, 8302, 8321, 8342, 8364, 8386, 8407,
            8429, 8450, 8472, 8494, 8515, 8537, 8558, 8580, 8599, 8618,
            8640, 8659, 8678, 8700, 8719, 8738, 8760, 8779, 8798, 8820,
            8837, 8854, 8870, 8890, 8906, 8923, 8942, 8959, 8976, 8995,
            9012, 9029, 9048, 9072, 9096, 9120, 9144, 9168, 9192, 9216,
            9240, 9264, 9288, 9312, 9336, 9362, 9389, 9415, 9444, 9470,
            9497, 9523, 9552, 9578, 9605, 9631, 9660, 9677, 9696, 9715,
            9732, 9751, 9770, 9787, 9806, 9826, 9842, 9862, 9881, 9900,
        ),
        // Abrams — 100.8V, LeaperKim embedded v9
        "0020" to intArrayOf(
            7560, 7596, 7634, 7673, 7711, 7750, 7788, 7824, 7862, 7901,
            7939, 7978, 8016, 8038, 8062, 8086, 8110, 8134, 8158, 8182,
            8206, 8230, 8254, 8278, 8302, 8321, 8342, 8364, 8386, 8407,
            8429, 8450, 8472, 8494, 8515, 8537, 8558, 8580, 8599, 8618,
            8640, 8659, 8678, 8700, 8719, 8738, 8760, 8779, 8798, 8820,
            8837, 8854, 8870, 8890, 8906, 8923, 8942, 8959, 8976, 8995,
            9012, 9029, 9048, 9072, 9096, 9120, 9144, 9168, 9192, 9216,
            9240, 9264, 9288, 9312, 9336, 9362, 9389, 9415, 9444, 9470,
            9497, 9523, 9552, 9578, 9605, 9631, 9660, 9677, 9696, 9715,
            9732, 9751, 9770, 9787, 9806, 9826, 9842, 9862, 9881, 9900,
        ),
        // Sherman — 100.8V, LeaperKim embedded v9
        "0010" to intArrayOf(
            7560, 7596, 7634, 7673, 7711, 7750, 7788, 7824, 7862, 7901,
            7939, 7978, 8016, 8038, 8062, 8086, 8110, 8134, 8158, 8182,
            8206, 8230, 8254, 8278, 8302, 8321, 8342, 8364, 8386, 8407,
            8429, 8450, 8472, 8494, 8515, 8537, 8558, 8580, 8599, 8618,
            8640, 8659, 8678, 8700, 8719, 8738, 8760, 8779, 8798, 8820,
            8837, 8854, 8870, 8890, 8906, 8923, 8942, 8959, 8976, 8995,
            9012, 9029, 9048, 9072, 9096, 9120, 9144, 9168, 9192, 9216,
            9240, 9264, 9288, 9312, 9336, 9362, 9389, 9415, 9444, 9470,
            9497, 9523, 9552, 9578, 9605, 9631, 9660, 9677, 9696, 9715,
            9732, 9751, 9770, 9787, 9806, 9826, 9842, 9862, 9881, 9900,
        ),
        // Firmware downloader — 4.2V, LeaperKim embedded v9
        "6661" to intArrayOf(
            315, 316, 318, 319, 321, 322, 324, 326, 327, 329,
            330, 332, 334, 334, 335, 336, 337, 338, 339, 340,
            341, 342, 343, 344, 345, 346, 347, 348, 349, 350,
            351, 352, 353, 353, 354, 355, 356, 357, 358, 359,
            360, 360, 361, 362, 363, 364, 365, 365, 366, 367,
            368, 368, 369, 370, 371, 371, 372, 373, 374, 374,
            375, 376, 377, 378, 379, 380, 381, 382, 383, 384,
            385, 386, 387, 388, 389, 390, 391, 392, 393, 394,
            395, 396, 398, 399, 400, 401, 402, 403, 404, 404,
            405, 406, 407, 407, 408, 409, 410, 410, 411, 413,
        ),
        // Firmware downloader — 4.2V, LeaperKim embedded v9
        "6660" to intArrayOf(
            315, 316, 318, 319, 321, 322, 324, 326, 327, 329,
            330, 332, 334, 334, 335, 336, 337, 338, 339, 340,
            341, 342, 343, 344, 345, 346, 347, 348, 349, 350,
            351, 352, 353, 353, 354, 355, 356, 357, 358, 359,
            360, 360, 361, 362, 363, 364, 365, 365, 366, 367,
            368, 368, 369, 370, 371, 371, 372, 373, 374, 374,
            375, 376, 377, 378, 379, 380, 381, 382, 383, 384,
            385, 386, 387, 388, 389, 390, 391, 392, 393, 394,
            395, 396, 398, 399, 400, 401, 402, 403, 404, 404,
            405, 406, 407, 407, 408, 409, 410, 410, 411, 413,
        ),
    )

    /**
     * The shared per-cell state-of-charge curve, in centivolts per cell (0.01 V), 100 points,
     * index 0 = 0 % and index 99 = 100 %.
     */
    internal val perCellCurveCentiVolts: IntArray = intArrayOf(
        315, 316, 318, 320, 321, 323, 324, 326, 328, 329,
        331, 332, 334, 335, 336, 337, 338, 339, 340, 341,
        342, 343, 344, 345, 346, 347, 348, 348, 349, 350,
        351, 352, 353, 354, 355, 356, 357, 358, 358, 359,
        360, 361, 362, 362, 363, 364, 365, 366, 367, 368,
        368, 369, 370, 370, 371, 372, 373, 373, 374, 375,
        376, 376, 377, 378, 379, 380, 381, 382, 383, 384,
        385, 386, 387, 388, 389, 390, 391, 392, 394, 395,
        396, 397, 398, 399, 400, 401, 402, 403, 404, 405,
        406, 406, 407, 408, 409, 409, 410, 411, 412, 412,
    )

    private fun model(
        name: String,
        hardwareCode: String,
        sNum: Int,
        systemVoltageVolts: Double,
        continuousSoftHardSet: Boolean,
        source: Source,
    ): VeteranModel {
        val cells = kotlin.math.round(systemVoltageVolts / NOMINAL_FULL_CELL_VOLTS).toInt()
        return VeteranModel(
            name = name,
            hardwareCode = hardwareCode,
            sNum = sNum,
            systemVoltageVolts = systemVoltageVolts,
            cells = cells,
            continuousSoftHardSet = continuousSoftHardSet,
            source = source,
            isFirmwareSentinel = cells <= 1,
        )
    }

    /** The table. Twelve entries: ten vehicles and two firmware sentinels. */
    val models: List<VeteranModel> = listOf(
        // live NOSFET endpoint, version 11
        model("APEX", "5010", 1, 151.2, true, Source.NOSFET_LIVE),
        model("AERO", "5020", 2, 126.0, true, Source.NOSFET_LIVE),

        // embedded LeaperKim table, version 9
        model("Patton-S", "0070", 10, 126.0, true, Source.LEAPERKIM_EMBEDDED),
        model("Sherman-L", "0060", 9, 151.2, true, Source.LEAPERKIM_EMBEDDED),
        model("LYNX", "0050", 8, 151.2, true, Source.LEAPERKIM_EMBEDDED),
        model("Patton", "0040", 7, 126.0, false, Source.LEAPERKIM_EMBEDDED),
        model("Sherman-s", "0030", 6, 100.8, false, Source.LEAPERKIM_EMBEDDED),
        model("ShermanMax", "0011", 5, 100.8, false, Source.LEAPERKIM_EMBEDDED),
        model("Abrams", "0020", 4, 100.8, false, Source.LEAPERKIM_EMBEDDED),
        model("Sherman", "0010", 3, 100.8, false, Source.LEAPERKIM_EMBEDDED),
        // Sentinels
        model("Firmware downloader", "6661", 2, 4.2, true, Source.LEAPERKIM_EMBEDDED),
        model("Firmware downloader", "6660", 1, 4.2, true, Source.LEAPERKIM_EMBEDDED),
    )

    val vehicles: List<VeteranModel> get() = models.filterNot { it.isFirmwareSentinel }

    /**
     * Resolve the vendor's four-character hardware code to a model.
     *
     * @return the model, or `null` for an unknown code.
     */
    fun lookup(hardwareCode: String?): VeteranModel? {
        val key = hardwareCode?.trim() ?: return null
        if (key.length != 4) return null
        return models.firstOrNull { it.hardwareCode == key }
    }

    /** Compatibility alias for earlier callers. */
    fun fromHardwareKey(key: String): VeteranModel? {
        val m = vehicles.firstOrNull { it.hardwareCode == key } ?: return null
        val prettyName = when (m.name) {
            "APEX" -> "Apex"
            "AERO" -> "Aero"
            "LYNX" -> "Lynx"
            else -> m.name
        }
        return m.copy(name = prettyName)
    }

    /** NOSFET parses the hex bytes 30,28,29 as a number, then zero-pads in decimal. */
    fun modernHardwareKey(byte30: Int, byte28: Int, byte29: Int): String {
        val raw = ((byte30 and 0xff) shl 16) or ((byte28 and 0xff) shl 8) or (byte29 and 0xff)
        return raw.toString().padStart(6, '0').take(4)
    }

    /**
     * A model's absolute state-of-charge curve in centivolts for the whole pack.
     */
    fun curveCentiVolts(m: VeteranModel): IntArray =
        exactCurves[m.hardwareCode]
            ?: IntArray(perCellCurveCentiVolts.size) { perCellCurveCentiVolts[it] * m.cells }

    /**
     * State of charge in whole percent from a raw pack voltage in centivolts.
     */
    fun stateOfChargePercent(m: VeteranModel, packCentiVolts: Int): Int? {
        if (m.isFirmwareSentinel) return null
        val curve = curveCentiVolts(m)
        if (packCentiVolts <= curve.first()) return 0
        if (packCentiVolts >= curve.last()) return 100
        for (i in 1 until curve.size) {
            if (packCentiVolts <= curve[i]) return i
        }
        return 100
    }

    fun stateOfChargePercentFromCells(cells: Int, packCentiVolts: Int): Int? {
        if (cells <= 1) return null
        val curve = IntArray(perCellCurveCentiVolts.size) { perCellCurveCentiVolts[it] * cells }
        if (packCentiVolts <= curve.first()) return 0
        if (packCentiVolts >= curve.last()) return 100
        for (i in 1 until curve.size) {
            if (packCentiVolts <= curve[i]) return i
        }
        return 100
    }

    /**
     * Unified state of charge lookup:
     * 1. Exact lookup from [hardwareCode] if recognized.
     * 2. If unknown (e.g. Aeon or uncatalogued firmware) and user provided [userSeriesCells],
     *    derives SOC from the shared single-cell curve.
     * 3. Otherwise returns `null` (safe per #17, no blind guess).
     */
    fun stateOfCharge(
        hardwareCode: String?,
        packCentiVolts: Int,
        userSeriesCells: Int? = null,
    ): Int? {
        val model = lookup(hardwareCode)
        if (model != null) {
            return stateOfChargePercent(model, packCentiVolts)
        }
        if (userSeriesCells != null && userSeriesCells > 1) {
            return stateOfChargePercentFromCells(userSeriesCells, packCentiVolts)
        }
        return null
    }
}
