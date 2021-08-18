package br.com.gamemods.j2nwc.internal

import br.com.gamemods.j2nwc.WorldConverter
import br.com.gamemods.nbtmanipulator.*
import br.com.gamemods.regionmanipulator.ChunkPos
import br.com.gamemods.regionmanipulator.Region
import net.md_5.bungee.api.ChatColor
import java.io.FileNotFoundException
import java.util.*
import kotlin.math.floor
import kotlin.math.max

private fun JavaBlock.commonBlockEntityData(id: String) = arrayOf(
    "id" to NbtString(id),
    "x" to NbtInt(blockPos.xPos),
    "y" to NbtInt(blockPos.yPos),
    "z" to NbtInt(blockPos.zPos),
    "isMoveable" to NbtByte(false)
)

private inline fun JavaBlock.createTileEntity(id: String, vararg tags: Pair<String, NbtTag>, action: (NbtCompound)->Unit = {}): NbtCompound {
    return NbtCompound(*commonBlockEntityData(id), *tags).also(action)
}

internal fun JavaBlock.toNukkit(
    regionPostConversionHooks: MutableList<PostConversionHook>,
    worldHooks: MutableList<PostWorldConversionHook>,
    worldConverter: WorldConverter
): NukkitBlock {
    val blockData = this.type.toNukkit(worldConverter)
    val waterlogged = type.properties?.getNullableString("waterlogged") == "true"
            || type.blockName in javaInheritedWaterlogging

    val nukkitTileEntity = when (blockData.blockId) {
        26 -> createTileEntity("Bed",
            "color" to NbtByte(when (type.blockName) {
                "minecraft:white_bed" -> 0
                "minecraft:orange_bed" -> 1
                "minecraft:magenta_bed" -> 2
                "minecraft:light_blue_bed" -> 3
                "minecraft:yellow_bed" -> 4
                "minecraft:lime_bed" -> 5
                "minecraft:pink_bed" -> 6
                "minecraft:gray_bed" -> 7
                "minecraft:light_gray_bed" -> 8
                "minecraft:cyan_bed" -> 9
                "minecraft:purple_bed" -> 10
                "minecraft:blue_bed" -> 11
                "minecraft:brown_bed" -> 12
                "minecraft:green_bed" -> 13
                "minecraft:red_bed" -> 14
                "minecraft:black_bed" -> 15
                else -> 14
            })
        )
        458 -> createTileEntity("Barrel") { nukkitEntity ->
            tileEntity?.copyJsonToLegacyTo(nukkitEntity, "CustomName")
            tileEntity?.toNukkitInventory(worldConverter, nukkitEntity)
        }
        23 -> createTileEntity("Dispenser") { nukkitEntity ->
            tileEntity?.toNukkitInventory(worldConverter, nukkitEntity)
        }
        125 -> createTileEntity("Dropper") { nukkitEntity ->
            tileEntity?.toNukkitInventory(worldConverter, nukkitEntity)
        }
        54 -> createTileEntity("Chest") { nukkitEntity ->
            tileEntity?.copyJsonToLegacyTo(nukkitEntity, "CustomName")
            //if (blockData.originalBedrock?.let { it.blockId == 458 && (it.data and 0x8) == 0x8 } == true) {
            // Note: Tried to persist the barrel open state after a chest conversion but it is not possible, chests are open by BlockEventPacket
            //}
            val needsSwapping = type.properties?.getString("facing").let { it == "south" || it == "west" }
            val pair = when (type.properties?.getString("type")) {
                "left" -> when (type.properties?.getString("facing")) {
                    "east" -> blockPos.xPos to blockPos.zPos +1
                    "south" -> blockPos.xPos -1 to blockPos.zPos
                    "west" -> blockPos.xPos to blockPos.zPos -1
                    else -> blockPos.xPos +1 to blockPos.zPos
                }
                "right" -> when (type.properties?.getString("facing")) {
                    "east" -> blockPos.xPos to blockPos.zPos -1
                    "south" -> blockPos.xPos +1 to blockPos.zPos
                    "west" -> blockPos.xPos to blockPos.zPos +1
                    else -> blockPos.xPos -1 to blockPos.zPos
                }
                else -> null
            }
            tileEntity?.toNukkitInventory(worldConverter, nukkitEntity)
            pair?.also { (x, z) ->
                nukkitEntity["pairx"] = x
                nukkitEntity["pairz"] = z

                if (needsSwapping) {
                    val y = blockPos.yPos
                    val pairedChunkPos = ChunkPos(floor(x / 16.0).toInt(), floor(z / 16.0).toInt())
                    val currentChunkPos =
                        ChunkPos(floor(blockPos.xPos / 16.0).toInt(), floor(blockPos.zPos / 16.0).toInt())

                    fun swapItems(nukkitRegion: Region) {
                        nukkitRegion[pairedChunkPos]?.level?.getCompoundList("TileEntities")
                            ?.find { it.getInt("x") == x && it.getInt("y") == y && it.getInt("z") == z }
                            ?.also { pairedEntity ->
                                if (pairedEntity.getNullableBooleanByte("--pair-processed--")) {
                                    pairedEntity.remove("--pair-processed--")
                                } else {
                                    val thisItems = nukkitEntity.getList("Items")
                                    val otherItems = pairedEntity.getList("Items")

                                    pairedEntity["Items"] = thisItems
                                    nukkitEntity["Items"] = otherItems
                                    nukkitEntity["--pair-processed--"] = true
                                }
                            }
                    }

                    val currentRegX = floor(currentChunkPos.xPos / 32.0).toInt()
                    val currentRegZ = floor(currentChunkPos.zPos / 32.0).toInt()
                    val pairedRegX = floor(pairedChunkPos.xPos / 32.0).toInt()
                    val pairedRegZ = floor(pairedChunkPos.zPos / 32.0).toInt()
                    if (currentRegX == pairedRegX && currentRegZ == pairedRegZ) {
                        regionPostConversionHooks += { _, nukkitRegion ->
                            swapItems(nukkitRegion)
                        }
                    } else {
                        worldHooks += { _, worldDir ->
                            try {
                                modifyRegion(worldDir, pairedRegX, pairedRegZ, ::swapItems)
                            } catch (e: FileNotFoundException) {
                                System.err.println(
                                    "Could not swap the double chest items between the chests $blockPos and ${BlockPos(
                                        x,
                                        y,
                                        z
                                    )} because the file r.$pairedRegX.$pairedRegZ.mca does not exists!"
                                )
                                System.err.println(e.toString())
                            }
                        }
                    }
                }
            }
        }
        130 -> createTileEntity("EnderChest")
        61, 62, 451, 469, 453, 454 -> createTileEntity(when (blockData.blockId) {
            61, 62 -> "Furnace"
            451, 469 -> "BlastFurnace"
            else -> "Smoker"
        }) { nukkitEntity ->
            tileEntity?.apply {
                copyJsonToLegacyTo(nukkitEntity, "CustomName")
                copyTo(nukkitEntity, "BurnTime")
                copyTo(nukkitEntity, "CookTime")
                try {
                    getNullableShort("CookTimeTotal")?.also {
                        nukkitEntity["BurnDuration"] = it
                }
                }catch (e: Exception) {
                    println("转换失败：" + blockData.blockId)
                }
                toNukkitInventory(worldConverter, nukkitEntity)
            }
        }
        117 -> createTileEntity("BrewingStand") { nukkitEntity ->
            tileEntity?.apply {
                copyJsonToLegacyTo(nukkitEntity, "CustomName")
                nukkitEntity["CookTime"] = getShort("BrewTime")
                val fuel = getNullableByte("Fuel") ?: 0
                val fuelTotal = if (fuel > 0) 20 else 0
                nukkitEntity["FuelTotal"] = fuelTotal.toShort()
                nukkitEntity["FuelAmount"] = fuel.toShort()
                toNukkitInventory(worldConverter, nukkitEntity) {
                    when {
                        it == 3 -> 0
                        it < 3 -> it + 1
                        else -> it
                    }
                }
            }
        }
        151, 178 -> createTileEntity("DaylightDetector")
        25 -> createTileEntity("Music") { nukkitEntity ->
            nukkitEntity["note"] = type.properties?.getString("note")?.toByte() ?: 0
            nukkitEntity["powered"] = type.properties?.getString("powered")?.toBoolean() ?: false
        }
        63,436,441,443,445,447,68,437,442,444,446,448 ->
            createTileEntity("Sign") { nukkitEntity ->
                val lines = StringBuilder()
                val color = when (tileEntity?.getNullableString("Color")) {
                    "white" -> ChatColor.WHITE
                    "orange" -> ChatColor.GOLD
                    "magenta" -> ChatColor.LIGHT_PURPLE
                    "light_blue" -> ChatColor.DARK_AQUA
                    "yellow" -> ChatColor.YELLOW
                    "lime" -> ChatColor.GREEN
                    "pink" -> ChatColor.LIGHT_PURPLE
                    "gray" -> ChatColor.DARK_GRAY
                    "light_gray" -> ChatColor.GRAY
                    "cyan" -> ChatColor.AQUA
                    "purple" -> ChatColor.DARK_PURPLE
                    "blue" -> ChatColor.DARK_BLUE
                    "brown" -> ChatColor.DARK_RED
                    "green" -> ChatColor.DARK_GREEN
                    "red" -> ChatColor.RED
                    "black" -> ChatColor.BLACK
                    else -> null
                }
                for (i in 1..4) {
                    val text = tileEntity?.getString("Text$i")?.fromJsonToLegacy() ?: ""
                    color?.let(lines::append)
                    lines.append(text).append('\n')
                }
                nukkitEntity["Text"] = lines.toString()
            }
        52 -> createTileEntity("MobSpawner") { nukkitEntity ->
            // Tile entity based on
            // https://github.com/Nukkit-coders/MobPlugin/blob/master/src/main/java/nukkitcoders/mobplugin/entities/block/BlockEntitySpawner.java
            tileEntity?.apply {
                copyTo(nukkitEntity, "SpawnRange")
                copyTo(nukkitEntity, "MinSpawnDelay")
                copyTo(nukkitEntity, "MaxSpawnDelay")
                copyTo(nukkitEntity, "MaxNearbyEntities")
                copyTo(nukkitEntity, "RequiredPlayerRange")
                nukkitEntity["EntityId"] = getNullableCompound("SpawnData")?.entityToId() ?: 12
            }
        }
        116 -> createTileEntity("EnchantTable") { nukkitEntity ->
            tileEntity?.copyJsonToLegacyTo(nukkitEntity, "CustomName")
        }
        144 -> createTileEntity("Skull") { nukkitEntity ->
            val rotation = type.properties?.getNullableString("rotation") ?: "0"
            val type = when (type.blockName.removePrefix("minecraft:")) {
                "skeleton_skull", "skeleton_wall_skull" -> 0
                "wither_skeleton_skull", "wither_skeleton_wall_skull" -> 1
                "zombie_head", "zombie_wall_head" -> 2
                "player_head", "player_wall_head" -> {
                    if (tileEntity?.containsKey("Owner") == true && worldConverter.skipSkinHeads) {
                        return if (waterlogged) {
                            NukkitBlock(blockPos, BlockData(8, 0, blockData, type), null, false)
                        } else {
                            NukkitBlock(blockPos, BlockData(0, 0, blockData, type), null, false)
                        }
                    } else {
                        3
                    }
                }
                "creeper_head", "creeper_wall_head" -> 4
                "dragon_head", "dragon_wall_head" -> 5
                else -> 0
            }
            nukkitEntity["Rot"] = rotation.toByte()
            nukkitEntity["SkullType"] = type.toByte()
            if ("_wall_" !in this.type.blockName) {
                blockData.data = 1
            }
        }
        140 -> createTileEntity("FlowerPot") { nukkitEntity ->
            val potted = when (type.blockName.removePrefix("minecraft:")) {
                "potted_oak_sapling" -> BlockData(6, 0)
                "potted_spruce_sapling" -> BlockData(6, 1)
                "potted_birch_sapling" -> BlockData(6, 2)
                "potted_jungle_sapling" -> BlockData(6, 3)
                "potted_acacia_sapling" -> BlockData(6, 4)
                "potted_dark_oak_sapling" -> BlockData(6, 5)
                "potted_fern" -> BlockData(31, 1)
                "potted_dandelion" -> BlockData(37, 0)
                "potted_poppy" -> BlockData(38, 0)
                "potted_blue_orchid" -> BlockData(38, 1)
                "potted_allium" -> BlockData(38, 2)
                "potted_azure_bluet" -> BlockData(38, 3)
                "potted_red_tulip" -> BlockData(38, 4)
                "potted_orange_tulip" -> BlockData(38, 5)
                "potted_white_tulip" -> BlockData(38, 6)
                "potted_pink_tulip" -> BlockData(38, 7)
                "potted_oxeye_daisy" -> BlockData(38, 8)
                "potted_cornflower" -> BlockData(38, 9)
                "potted_lily_of_the_valley" -> BlockData(38, 10)
                "potted_brown_mushroom" -> BlockData(39, 0)
                "potted_red_mushroom" -> BlockData(40, 0)
                "potted_dead_bush" -> BlockData(32, 0)
                "potted_cactus" -> BlockData(81, 0)
                "potted_bamboo" ->
                    if (worldConverter.targetType.maxBlockId > 418) {
                        BlockData(418, 0)
                    } else {
                        BlockData(38, 0)
                    }
                "potted_wither_rose" ->
                    if (worldConverter.targetType.maxBlockId > 471) {
                        BlockData(471, 0)
                    } else {
                        BlockData(38, 2)
                    }
                else -> BlockData(0, 0)
            }
            potted.blockId.takeIf { it != 0 }?.let {
                nukkitEntity["item"] = it
            }

            potted.data.takeIf { it != 0 }?.let {
                nukkitEntity["data"] = it
            }
        }
        118 -> createTileEntity("Cauldron")
        138 -> createTileEntity("Beacon") { nukkitEntity ->
            tileEntity?.apply {
                val primary = javaStatusEffectNames[getNullableInt("Primary") ?: 0]?.let { java2bedrockEffectIds[it] }
                val secondary = javaStatusEffectNames[getNullableInt("Secondary") ?: 0]?.let { java2bedrockEffectIds[it] }
                primary?.let { nukkitEntity["Primary"] = it }
                secondary?.let { nukkitEntity["Secondary"] = it }
            }
        }
        33, 29 -> createTileEntity("PistonArm") { nukkitEntity ->
            val sticky = when (type.blockName) {
                "minecraft:piston" -> false
                "minecraft:sticky_piston" -> true
                else -> type.properties?.getString("type") == "sticky"
            }
            val extended = type.properties?.getNullableString("extended") == "true"
            nukkitEntity["Sticky"] = sticky
            nukkitEntity["facing"] = when (type.properties?.getNullableString("facing")) {
                "up" -> 1
                "north" -> 2
                "south" -> 3
                "west" -> 4
                "east" -> 5
                null, "down" -> 0
                else -> 0
            }
            if (extended) {
                nukkitEntity["Progress"] = 1F
                nukkitEntity["LastProgress"] = 1F
                nukkitEntity["State"] = 2
                nukkitEntity["NewState"] = 2
            } else {
                nukkitEntity["Progress"] = 0F
                nukkitEntity["LastProgress"] = 0F
                nukkitEntity["State"] = 0
                nukkitEntity["NewState"] = 0
            }
        }
        149, 150 -> createTileEntity("Comparator") { nukkitEntity ->
            tileEntity?.apply {
                copyTo(nukkitEntity, "OutputSignal")
            }
        }
        154 -> createTileEntity("Hopper") { nukkitEntity ->
            tileEntity?.apply {
                copyTo(nukkitEntity, "TransferCooldown")
                toNukkitInventory(worldConverter, nukkitEntity)
            }
        }
        84 -> createTileEntity("Jukebox") { nukkitEntity ->
            tileEntity?.getNullableCompound("RecordItem")?.let {
                nukkitEntity["RecordItem"] = it.toNukkitItem(worldConverter)
            }
        }
        205, 218 -> createTileEntity("ShulkerBox") { nukkitEntity ->
            val facing = when (type.properties?.getNullableString("facing")) {
                "down" -> 0
                "up" -> 1
                "north" -> 3
                "west" -> 4
                "east" -> 5
                else -> 0
            }
            nukkitEntity["facing"] = facing.toByte()
            tileEntity?.apply {
                copyJsonToLegacyTo(nukkitEntity, "CustomName")
                toNukkitInventory(worldConverter, nukkitEntity)
            }
        }
        176, 177 -> createTileEntity("Banner") { nukkitEntity ->
            val baseColor = when (type.blockName.removePrefix("minecraft:").removeSuffix("_banner").removeSuffix("_wall")) {
                "white" -> 15
                "orange" -> 14
                "magenta" -> 13
                "light_blue" -> 12
                "yellow" -> 11
                "lime" -> 10
                "pink" -> 9
                "gray" -> 8
                "light_gray" -> 7
                "cyan" -> 6
                "purple" -> 5
                "blue" -> 4
                "brown" -> 3
                "green" -> 2
                "red" -> 1
                "black" -> 0
                else -> 0
            }
            nukkitEntity["Base"] = baseColor
            tileEntity?.getNullableCompoundList("Patterns")?.also { patterns ->
                val nukkitPatterns = NbtList<NbtCompound>()
                patterns.forEach { pattern ->
                    val nukkitPattern = NbtCompound()
                    val patternCode = pattern.getNullableString("Pattern") ?: return@forEach
                    val patternColor = pattern.getNullableInt("Color") ?: return@forEach
                    nukkitPattern["Pattern"] = patternCode
                    nukkitPattern["Color"] = 15 - patternColor
                    nukkitPatterns += nukkitPattern
                }
                nukkitEntity["Patterns"] = nukkitPatterns
            }
        }
        449 -> createTileEntity("Lectern") { nukkitEntity ->
            tileEntity?.let { javaEntity ->
                javaEntity.getNullableInt("Page")?.let { nukkitEntity["page"] = it }
                javaEntity.getNullableCompound("Book")?.let { nukkitEntity["book"] = it.toNukkitItem(worldConverter) }
            }
        }
        474, 473 -> createTileEntity("Beehive") { nukkitEntity ->
            val occupants = NbtList<NbtCompound>()
            tileEntity?.getNullableCompoundList("Bees")?.forEach { occupant ->
                val minTicks = occupant.getNullableInt("MinOccupationTicks") ?: 0
                val ticks = occupant.getNullableInt("TicksInHive") ?: 0
                val ticksLeft = max(0, minTicks - ticks)
                val entityData = occupant.getNullableCompound("EntityData") ?: return@forEach
                val entity = toNukkitEntity(entityData, null, null, null, regionPostConversionHooks, worldHooks, worldConverter) ?: return@forEach
                val nukkitOccupant = NbtCompound()
                nukkitOccupant["ActorIdentifier"] = "Bee"
                nukkitOccupant["TicksLeftToStay"] = ticksLeft
                nukkitOccupant["SaveData"] = entity
                //TODO HasNectar
                nukkitOccupant["Muted"] = false
                occupants += nukkitOccupant
            }
            if (!occupants.isEmpty()) {
                nukkitEntity["Occupants"] = occupants
            }
        }
        412 -> createTileEntity("Conduit") // TODO Target and Active
        464 -> createTileEntity("Campfire") { nukkitEntity ->
            tileEntity?.also { javaEntity ->
                javaEntity.getNullableCompoundList("Items")?.asSequence()
                    ?.sortedBy { it.getInt("Slot") }
                    ?.map { it.toNukkitItem(worldConverter) }
                    ?.forEachIndexed { slot, item ->
                        nukkitEntity["Item${slot + 1}"] = item
                    }
                val cookTime = javaEntity.getNullableIntArray("CookingTimes") ?: intArrayOf(0, 0, 0, 0)
                val cookTimeTotal = javaEntity.getNullableIntArray("CookingTotalTimes") ?: intArrayOf(600, 600, 600, 600)
                for (i in 0..3) {
                    val remainingCookTime = max(0, cookTimeTotal[i] - cookTime[i])
                    nukkitEntity["ItemTime${i + 1}"] = remainingCookTime
                }
            }
        }
        461 -> createTileEntity("Bell")
        else -> null
    }

    return NukkitBlock(blockPos, blockData, nukkitTileEntity, waterlogged)
}

internal data class BlockData(var blockId: Int, var data: Int, val originalBedrock: BlockData? = null, val originalJava: JavaPalette? = null) {
    val byteBlockId: Byte get() = (blockId and 0xFF).toByte()
    val byteBlockIdExtra: Byte get() = ((blockId shr 8) and 0xFF).toByte()
    val dataFirstPart: Int get() = data and 0x0F
    val dataSecondPart: Int get() = (data shr 4) and 0x0F
}
internal fun JavaPalette.toNukkit(worldConverter: WorldConverter): BlockData {
    val propertiesId = properties
        ?.mapValuesTo(TreeMap()) { (it.value as NbtString).value }
        ?.map { "${it.key}-${it.value}" }
        ?.joinToString(";", prefix = ";")
        ?: ""

    val stateId = "$blockName$propertiesId".removePrefix("minecraft:").replace(':', '-').toLowerCase()

    val bedrockState = java2bedrockStates[stateId]
    if (bedrockState == null) {
        System.err.println("Missing block state mapping for $stateId")
    }
    val prop = bedrockState ?: java2bedrockStates[blockName] ?: "248,0"
    val originalBedrock = propertyToBlockData(prop)
    val nukkitProp = worldConverter.targetType.bedrock2target.getProperty("B,$prop") ?: prop
    val nukkit = propertyToBlockData(nukkitProp, originalBedrock, this)
    check(nukkit.blockId in 0..worldConverter.targetType.maxBlockId) {
        "Block id unsupported by the target ${worldConverter.targetType}: $nukkit. The maximum ID is ${worldConverter.targetType.maxBlockId}"
    }
    check (nukkit.data in 0..worldConverter.targetType.maxDataValue) {
        "Block data unsupported by the target ${worldConverter.targetType}: $nukkit. The maximum data is ${worldConverter.targetType.maxDataValue}"
    }

    return nukkit
}

private fun propertyToBlockData(prop: String, originalBedrock: BlockData? = null, originalJava: JavaPalette? = null): BlockData {
    val ids = prop.split(',', limit = 2)
    val blockId = ids[0].toInt()
    val blockData = ids.getOrElse(1) { "0" }.toInt()
    return BlockData(blockId, blockData, originalBedrock, originalJava)
}
