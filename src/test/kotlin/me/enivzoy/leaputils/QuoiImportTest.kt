package me.enivzoy.leaputils

import me.enivzoy.leaputils.pov.PresetCondJson
import me.enivzoy.leaputils.pov.QuoiImport
import me.enivzoy.leaputils.pov.SpLandmark
import me.enivzoy.leaputils.pov.SpStage
import me.enivzoy.leaputils.pov.SavepointEngine
import me.enivzoy.leaputils.pov.SavepointPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuoiImportTest {

    // the importer refuses a plan that leaves planner cells empty, so tests must fill every row
    private val fullCells: String = SpStage.entries.joinToString(",\n") { stage ->
        (1..stage.steps).joinToString(",\n") { step -> "\"${stage.id}.$step\": { \"*\": \"FIRST\" }" }
    }

    private val presetJson = """
        {
          "id": "quoi",
          "label": "test",
          "cells": {
            $fullCells
          },
          "conds": {
            "P1.1": { "subject": "TARGET", "mode": "NEAR", "landmark": "crystal_a", "errorMinDistance": 3.0, "errorMaxDistance": 15.0 },
            "P1.2": { "subject": "TARGET", "mode": "NEAR", "landmark": "user_spot" }
          }
        }
    """.trimIndent()

    private val landmarkJson = """
        {
          "crystal_a": { "x": 82.5, "y": 238.0, "z": 50.5 },
          "crystal_b": { "x": 64.5, "y": 238.0, "z": 50.5 }
        }
    """.trimIndent()

    // ---- landmark prefixing ----

    @Test
    fun prefixIsAddedOnceOnly() {
        assertEquals("quoicnfg-crystal_a", QuoiImport.prefixedName("crystal_a"))
        assertEquals("quoicnfg-crystal_a", QuoiImport.prefixedName("  crystal_a  "))
        assertEquals("quoicnfg-crystal_a", QuoiImport.prefixedName("quoicnfg-crystal_a"))
        assertEquals("", QuoiImport.prefixedName(""))
        assertEquals("", QuoiImport.prefixedName("   "))
    }

    @Test
    fun siblingLandmarksUrlUsesSameFolder() {
        assertEquals(
            "https://raw.githubusercontent.com/Enivzoy/POV-Preview/LeapUtils/presets/quoi-landmarks.json",
            QuoiImport.landmarksUrl("https://raw.githubusercontent.com/Enivzoy/POV-Preview/LeapUtils/presets/quoi.json"),
        )
        assertEquals(
            "https://example.com/a/b/quoi-landmarks.json",
            QuoiImport.landmarksUrl("https://example.com/a/b/"),
        )
        assertEquals(
            "https://example.com/quoi-landmarks.json",
            QuoiImport.landmarksUrl("https://example.com/quoi.json"),
        )
        assertNull(QuoiImport.landmarksUrl("quoi.json"))
    }

    @Test
    fun landmarksParseFlatAndWrapped() {
        val flat = QuoiImport.parseLandmarks(landmarkJson)
        assertNotNull(flat)
        assertEquals(SpLandmark(82.5, 238.0, 50.5), flat["crystal_a"])

        val wrapped = QuoiImport.parseLandmarks("""{"landmarks":{"spot":{"x":1,"y":2,"z":3}}}""")
        assertNotNull(wrapped)
        assertEquals(SpLandmark(1.0, 2.0, 3.0), wrapped["spot"])
    }

    @Test
    fun landmarksRejectMissingCoordinates() {
        assertNull(QuoiImport.parseLandmarks("""{"bad":{"x":1,"y":2}}"""))
        assertNull(QuoiImport.parseLandmarks("not json"))
        assertNull(QuoiImport.parseLandmarks("{}"))
    }

    // ---- plan preparation ----

    @Test
    fun preparePrefixesLandmarksAndRewritesCondReferences() {
        val prep = QuoiImport.prepare(presetJson, landmarkJson)
        assertEquals(setOf("quoicnfg-crystal_a", "quoicnfg-crystal_b"), prep.landmarks.keys)
        assertEquals("quoicnfg-crystal_a", prep.preset.conds!!["P1.1"]!!.landmark)
        assertEquals(3.0, prep.preset.conds!!["P1.1"]!!.errorMinDistance)
        assertEquals(15.0, prep.preset.conds!!["P1.1"]!!.errorMaxDistance)
        assertEquals(1, prep.condsMissingInterval)
    }

    @Test
    fun condOnForeignLandmarkIsLeftAlone() {
        val prep = QuoiImport.prepare(presetJson, landmarkJson)
        // user_spot is not part of the imported landmark file, so it must not be prefixed
        assertEquals("user_spot", prep.preset.conds!!["P1.2"]!!.landmark)
        assertNull(prep.preset.conds!!["P1.2"]!!.errorMinDistance)
    }

    @Test
    fun missingPlannerRowIsRejectedByName() {
        val preset = presetJson.replace("\"P1.2\": { \"*\": \"FIRST\" },\n", "")
        val err = assertFailsWith<IllegalArgumentException> { QuoiImport.prepare(preset, landmarkJson) }
        assertTrue(err.message!!.contains("P1.2"))
    }

    @Test
    fun invalidIntervalIsRejectedWithCondKey() {
        val preset = presetJson.replace("\"errorMaxDistance\": 15.0", "\"errorMaxDistance\": 1.0")
        val err = assertFailsWith<IllegalArgumentException> { QuoiImport.prepare(preset, landmarkJson) }
        assertTrue(err.message!!.contains("P1.1"))
        assertTrue(err.message!!.contains("below min"))
    }

    @Test
    fun emptyLandmarksFileIsRejected() {
        assertFailsWith<IllegalArgumentException> { QuoiImport.prepare(presetJson, "{}") }
    }

    @Test
    fun malformedPresetIsRejected() {
        assertFailsWith<IllegalArgumentException> { QuoiImport.prepare("{oops", landmarkJson) }
    }

    // ---- shipped defaults ----

    @Test
    fun shippedQuoiPresetFillsEveryPlannerCell() {
        val preset = QuoiImport.bundledPreset()
        assertNotNull(preset)
        val marks = QuoiImport.bundledLandmarks()
        assertNotNull(marks)

        val prep = QuoiImport.prepare(preset, marks)
        assertEquals(0, prep.condsMissingInterval, "every shipped cond must carry min/max")

        val cells = prep.preset.cells!!
        for (stage in SpStage.entries) {
            for (step in 1..stage.steps) {
                val key = "${stage.id}.$step"
                val row = cells[key]
                assertNotNull(row, "missing planner row $key")
                assertTrue(
                    row.containsKey("*") || SavepointEngine.LEAP_CLASSES.all { row.containsKey(it.name) },
                    "row $key must fill every class",
                )
                assertFalse(row.values.any { it == "NONE" }, "row $key must have targets")
            }
        }
    }

    @Test
    fun shippedCondsReferenceImportedLandmarks() {
        val prep = QuoiImport.prepare(QuoiImport.bundledPreset()!!, QuoiImport.bundledLandmarks()!!)
        for ((key, cond) in prep.preset.conds!!) {
            val lm = cond.landmark.orEmpty()
            assertTrue(lm.startsWith(QuoiImport.PREFIX), "cond $key landmark '$lm' must be prefixed")
            assertTrue(lm in prep.landmarks.keys, "cond $key references unknown landmark '$lm'")
            assertEquals(3.0, cond.errorMinDistance, "cond $key min")
            assertEquals(15.0, cond.errorMaxDistance, "cond $key max")
        }
    }

    @Test
    fun shippedLandmarksResolveToValidSpots() {
        val marks = QuoiImport.parseLandmarks(QuoiImport.bundledLandmarks()!!)
        assertNotNull(marks)
        for ((name, lm) in marks) {
            assertTrue(lm.x.isFinite() && lm.y.isFinite() && lm.z.isFinite(), "$name has a non-finite coord")
        }
        // the seven built-in F7 AABB centres must all be present so conds can resolve
        for (name in listOf(
            "crystal_a", "crystal_b", "yellow_pad", "purple_pad", "between_pads", "middle_platform", "i4_spot",
        )) {
            assertNotNull(marks[name], "missing default landmark $name")
        }
    }

    // ---- round trip through the preset json ----

    @Test
    fun preparedPresetSurvivesSaveAndReload() {
        val prep = QuoiImport.prepare(presetJson, landmarkJson)
        val json = SavepointPresets.toJson(prep.preset)
        val back = SavepointPresets.fromJson(json)
        assertNotNull(back)
        val cond1 = back.conds!!["P1.1"]!!
        assertEquals("quoicnfg-crystal_a", cond1.landmark)
        assertEquals(3.0, cond1.errorMinDistance)
        assertEquals(15.0, cond1.errorMaxDistance)

        val cond = SavepointPresets.toCond(cond1)
        assertTrue(cond.hasErrorInterval)
        assertTrue(cond.distanceOk(3.0, 3.0, 15.0))
        assertTrue(cond.distanceOk(15.0, 3.0, 15.0))
        assertFalse(cond.distanceOk(2.9, 3.0, 15.0))
        assertFalse(cond.distanceOk(15.1, 3.0, 15.0))
    }

    @Test
    fun legacyCondWithoutIntervalStaysLegacy() {
        val legacy = PresetCondJson(subject = "TARGET", mode = "NEAR", landmark = "quoicnfg-crystal_a")
        val cond = SavepointPresets.toCond(legacy)
        assertFalse(cond.hasErrorInterval)
        assertTrue(cond.distanceOk(7.9, 3.0, 15.0))
        assertFalse(cond.distanceOk(8.1, 3.0, 15.0))
    }
}