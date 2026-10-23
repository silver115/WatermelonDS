import me.magnum.melonds.pokewalker.CryArchive
import java.io.RandomAccessFile

fun main(args: Array<String>) {
    val f = RandomAccessFile(args[0], "r")
    val src = object : CryArchive.Source {
        override val size = f.length()
        override fun read(pos: Long, len: Int): ByteArray {
            val b = ByteArray(len)
            f.seek(pos)
            f.readFully(b)
            return b
        }
    }
    val out = StringBuilder("{")
    for (species in intArrayOf(25, 26)) {
        val cry = CryArchive.load(src, species)
        out.append("\"$species\":{\"rate\":${cry.sampleRate},\"samples\":[${cry.samples.joinToString(",")}]},")
    }
    for (species in intArrayOf(27, 600, 3)) {
        val msg = try { CryArchive.load(src, species); "loaded?!" } catch (e: CryArchive.FormatException) { e.message }
        out.append("\"err$species\":\"$msg\",")
    }
    out.append("\"path\":\"${CryArchive.findFile(src, "data/sound/gs_sound_data.sdat")}\",")
    out.append("\"missing\":\"${CryArchive.findFile(src, "data/sound/none.sdat")}\"}")
    println(out)
}
