package rain.gtetcore.gtet.common.item.tool

import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

class DebugBlockPattern {

    lateinit var structureDir: Array<RelativeDirection>
    lateinit var pattern: Array<Array<String>>
    lateinit var aisleRepetitions: Array<IntArray>
    lateinit var symbolMap: MutableMap<Char, MutableSet<String>>

    @JvmField
    var legend: MutableMap<Block, Char> = HashMap()

    constructor() {
        symbolMap = HashMap()
        structureDir = arrayOf(
            RelativeDirection.LEFT, RelativeDirection.UP, RelativeDirection.FRONT
        )
    }

    constructor(world: Level, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) : this() {
        pattern = Array(1 + maxX - minX) { Array(1 + maxY - minY) { "" } }
        aisleRepetitions = Array(pattern.size) { IntArray(2) }
        for (aisleRepetition in aisleRepetitions) {
            aisleRepetition[0] = 1
            aisleRepetition[1] = 1
        }

        legend[Blocks.AIR] = ' '

        var c = 'A' // auto

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val builder = StringBuilder()
                for (z in minZ..maxZ) {
                    val block = world.getBlockState(BlockPos(x, y, z)).block
                    if (!legend.containsKey(block)) {
                        legend[block] = c
                        val name = c.toString()
                        symbolMap.getOrPut(c) { HashSet() }.add(name) // any
                        c++
                    }
                    builder.append(legend[block])
                }
                pattern[x - minX][y - minY] = builder.toString()
            }
        }
        val dirs = getDir(Direction.NORTH)
        changeDir(dirs[0], dirs[1], dirs[2])
    }

    fun changeDir(charDir: RelativeDirection, stringDir: RelativeDirection, aisleDir: RelativeDirection) {
        if (charDir.isSameAxis(stringDir) || stringDir.isSameAxis(aisleDir) || aisleDir.isSameAxis(charDir)) return
        val sizeAisle = if (structureDir[0].isSameAxis(aisleDir)) pattern[0][0].length
        else if (structureDir[1].isSameAxis(aisleDir)) pattern[0].size
        else pattern.size
        val sizeString = if (structureDir[0].isSameAxis(stringDir)) pattern[0][0].length
        else if (structureDir[1].isSameAxis(stringDir)) pattern[0].size
        else pattern.size
        val sizeChar = if (structureDir[0].isSameAxis(charDir)) pattern[0][0].length
        else if (structureDir[1].isSameAxis(charDir)) pattern[0].size
        else pattern.size
        val newPattern = Array(sizeAisle) { Array(sizeString) { CharArray(sizeChar) } }
        for (i in pattern.indices) {
            for (j in pattern[0].indices) {
                for (k in pattern[0][0].indices) {
                    val c = pattern[i][j][k]
                    var x = 0
                    var y = 0
                    var z = 0
                    if (structureDir[2].isSameAxis(aisleDir)) {
                        if (structureDir[2] === aisleDir) {
                            x = i
                        } else {
                            x = pattern.size - i - 1
                        }
                    } else if (structureDir[2].isSameAxis(stringDir)) {
                        if (structureDir[2] === stringDir) {
                            y = i
                        } else {
                            y = pattern.size - i - 1
                        }
                    } else if (structureDir[2].isSameAxis(charDir)) {
                        if (structureDir[2] === charDir) {
                            z = i
                        } else {
                            z = pattern.size - i - 1
                        }
                    }

                    if (structureDir[1].isSameAxis(aisleDir)) {
                        if (structureDir[1] === aisleDir) {
                            x = j
                        } else {
                            x = pattern[0].size - j - 1
                        }
                    } else if (structureDir[1].isSameAxis(stringDir)) {
                        if (structureDir[1] === stringDir) {
                            y = j
                        } else {
                            y = pattern[0].size - j - 1
                        }
                    } else if (structureDir[1].isSameAxis(charDir)) {
                        if (structureDir[1] === charDir) {
                            z = j
                        } else {
                            z = pattern[0].size - j - 1
                        }
                    }

                    if (structureDir[0].isSameAxis(aisleDir)) {
                        if (structureDir[0] === aisleDir) {
                            x = k
                        } else {
                            x = pattern[0][0].length - k - 1
                        }
                    } else if (structureDir[0].isSameAxis(stringDir)) {
                        if (structureDir[0] === stringDir) {
                            y = k
                        } else {
                            y = pattern[0][0].length - k - 1
                        }
                    } else if (structureDir[0].isSameAxis(charDir)) {
                        if (structureDir[0] === charDir) {
                            z = k
                        } else {
                            z = pattern[0][0].length - k - 1
                        }
                    }
                    newPattern[x][y][z] = c
                }
            }
        }

        pattern = Array(newPattern.size) { Array(newPattern[0].size) { "" } }
        for (i in pattern.indices) {
            for (j in pattern[0].indices) {
                val builder = StringBuilder()
                for (c in newPattern[i][j]) {
                    builder.append(c)
                }
                pattern[i][j] = builder.toString()
            }
        }

        aisleRepetitions = Array(pattern.size) { IntArray(2) }
        for (aisleRepetition in aisleRepetitions) {
            aisleRepetition[0] = 1
            aisleRepetition[1] = 1
        }

        structureDir = arrayOf(charDir, stringDir, aisleDir)
    }

    @Suppress("all")
    fun copy(): DebugBlockPattern {
        val newPattern = DebugBlockPattern()
        System.arraycopy(structureDir, 0, newPattern.structureDir, 0, structureDir.size)

        newPattern.pattern = Array(pattern.size) { Array(pattern[0].size) { "" } }
        for (i in pattern.indices) {
            System.arraycopy(pattern[i], 0, newPattern.pattern[i], 0, pattern[i].size)
        }

        newPattern.aisleRepetitions = Array(aisleRepetitions.size) { IntArray(2) }
        for (i in aisleRepetitions.indices) {
            System.arraycopy(
                aisleRepetitions[i], 0, newPattern.aisleRepetitions[i], 0, aisleRepetitions[i].size
            )
        }

        symbolMap.forEach { (k, v) -> newPattern.symbolMap[k] = HashSet(v) }

        return newPattern
    }

    companion object {

        @JvmStatic
        fun getDir(facing: Direction?): Array<RelativeDirection> {
            if (facing === Direction.WEST) {
                return arrayOf(
                    RelativeDirection.LEFT, RelativeDirection.UP, RelativeDirection.BACK
                )
            } else if (facing === Direction.EAST) {
                return arrayOf(
                    RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.FRONT
                )
            } else if (facing === Direction.NORTH) {
                return arrayOf(
                    RelativeDirection.BACK, RelativeDirection.UP, RelativeDirection.RIGHT
                )
            } else if (facing === Direction.SOUTH) {
                return arrayOf(
                    RelativeDirection.FRONT, RelativeDirection.UP, RelativeDirection.LEFT
                )
            } else if (facing === Direction.DOWN) {
                return arrayOf(
                    RelativeDirection.RIGHT, RelativeDirection.BACK, RelativeDirection.UP
                )
            } else {
                return arrayOf(
                    RelativeDirection.LEFT, RelativeDirection.FRONT, RelativeDirection.UP
                )
            }
        }
    }
}