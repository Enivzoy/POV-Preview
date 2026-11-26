package me.enivzoy.leaputils.pov

import me.enivzoy.leaputils.dungeonpack.pack.PackSelfTest
import me.enivzoy.leaputils.dungeonpack.pack.doors.DoorPack
import me.enivzoy.leaputils.dungeonpack.pack.secrets.SecretPack
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// the bundled packs must ship, and a cache cut short by a crash must heal instead of breaking the draw
class PovCacheTest {

    @Test fun bundledDoorAndSecretPacksArePresent() {
        assertTrue(PackSelfTest.run().isEmpty(), "bundled packs missing")
        // the packs themselves parse and hold data: an empty file would pass a mere presence check
        assertTrue(DoorPack.bundled.styles.isNotEmpty(), "door pack has no styles")
        assertTrue(SecretPack.bundled.designs.isNotEmpty(), "secret pack has no designs")
    }

    @Test fun meshDiskKeepsWholeEntriesAndCutsATornTail() {
        val dir = Files.createTempDirectory("leaputils-mesh")
        try {
            val file = dir.resolve("cache.bin")
            DataOutputStream(Files.newOutputStream(file).buffered()).use { out ->
                out.writeUTF("a"); out.writeInt(2); out.writeInt(3); out.write(byteArrayOf(1, 2, 3))
                out.writeUTF("b"); out.writeInt(1); out.writeInt(2); out.write(byteArrayOf(9, 9))
            }
            val whole = Files.size(file)
            // a key and header with no bytes after it: the write the crash interrupted
            DataOutputStream(Files.newOutputStream(file, java.nio.file.StandardOpenOption.APPEND).buffered()).use { out ->
                out.writeUTF("c"); out.writeInt(4); out.writeInt(100); out.write(byteArrayOf(7))
            }

            val index = HashMap<String, MeshDisk.Entry>()
            val at = MeshIndex.loadEntries(file, index)

            assertEquals(whole, at)
            assertEquals(whole, Files.size(file))
            assertEquals(setOf("a", "b"), index.keys)
            assertEquals(3, index.getValue("a").length)
            assertEquals(1, index.getValue("b").count)
        } finally {
            runCatching { Files.deleteIfExists(dir.resolve("cache.bin")) }
            runCatching { Files.deleteIfExists(dir) }
        }
    }

    @Test fun edgeIndexIsRebuiltWhenItsHeaderOverrunsTheFile() {
        val dir = Files.createTempDirectory("leaputils-edges")
        try {
            val file = dir.resolve("edges.idx")
            DataOutputStream(Files.newOutputStream(file).buffered()).use { out ->
                out.writeInt(1_000_000) // far more pairs than the file holds
                out.writeInt(1); out.writeInt(2)
            }

            val built = intArrayOf(11, 22, 33) to intArrayOf(1, 2, 3)
            val got = assertNotNull(RoomEdges.readOrRebuild(file) { built })

            assertEquals(built.first.toList(), got.first.toList())
            assertEquals(built.second.toList(), got.second.toList())
            val again = assertNotNull(RoomEdges.readIndex(file))
            assertEquals(built.first.toList(), again.first.toList())
            assertEquals(built.second.toList(), again.second.toList())
        } finally {
            runCatching { Files.deleteIfExists(dir.resolve("edges.idx")) }
            runCatching { Files.deleteIfExists(dir) }
        }
    }

    @Test fun edgeIndexSurvivesATornWrite() {
        val dir = Files.createTempDirectory("leaputils-edges-torn")
        try {
            val file = dir.resolve("edges.idx")
            DataOutputStream(Files.newOutputStream(file).buffered()).use { out ->
                out.writeInt(2)
                out.writeInt(11); out.writeInt(1)
                out.writeInt(22) // the second pair's place, its value never made it
            }

            val built = intArrayOf(7) to intArrayOf(8)
            val got = assertNotNull(RoomEdges.readOrRebuild(file) { built })
            assertEquals(listOf(7), got.first.toList())

            val stream = DataInputStream(Files.newInputStream(file).buffered()).use { inp ->
                val n = inp.readInt()
                List(n) { inp.readInt() to inp.readInt() }
            }
            assertEquals(listOf(7 to 8), stream)
        } finally {
            runCatching { Files.deleteIfExists(dir.resolve("edges.idx")) }
            runCatching { Files.deleteIfExists(dir) }
        }
    }
}