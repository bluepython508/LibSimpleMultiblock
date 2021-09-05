package io.github.bluepython508.libsimplemultiblock

import kotlinx.serialization.json.*
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceType
import net.minecraft.state.StateManager
import net.minecraft.state.property.BooleanProperty
import net.minecraft.tag.BlockTags
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3i
import net.minecraft.util.registry.Registry
import net.minecraft.world.World
import net.minecraft.world.WorldView
import java.io.InputStream

internal const val MODID = "libsimplemultiblock"

internal operator fun Vec3i.minus(other: Vec3i): Vec3i = Vec3i(x - other.x, y - other.y, z - other.z)

internal val allPatterns: MutableMap<Identifier, Pattern> = mutableMapOf()

val JsonElement.string: String? get() = (this as? JsonPrimitive)?.run { content.takeIf { isString } }

val JsonElement.array: JsonArray? get() = this as? JsonArray

fun JsonObject.array(key: String): JsonArray? = get(key)?.array

fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

fun JsonObject.string(key: String): String? = get(key)?.string

fun JsonObject.ident(key: String): Identifier? = string(key)?.let(Identifier::tryParse)

internal fun matches(it: JsonElement): ((BlockState) -> Boolean)? {
    if (it.string == "base") {
            return { true }
    } else if (it is JsonObject) {
        return it.ident("block")?.let(Registry.BLOCK::get)
            ?.let { block -> { state: BlockState -> state.isOf(block) } }
            ?: it.ident("tag").let(BlockTags.getTagGroup()::getTag)
                .let { tag -> { state: BlockState -> state.isIn(tag) } }
    }
    return null
}

internal class Pattern(val id: Identifier, private val pattern: Map<Vec3i, (BlockState) -> Boolean>) {
    fun isValid(base: BlockPos, world: WorldView): Boolean =
        pattern.entries.all {
            it.value(world.getBlockState(base.add(it.key)))
        }

    companion object {
        fun load(stream: InputStream): Pattern? {
            val pat = Json.parseToJsonElement(stream.reader().readText()) as? JsonObject ?: return null
            val id = pat.ident("id") ?: return null
            val key = pat.obj("key")?.map {
                (it.key.singleOrNull().takeUnless { c -> c == ' ' } ?: return null) to (it.value.let(::matches)
                    ?: return null)
            }?.toMap() ?: return null
            val baseKey = pat.obj("key")?.entries?.find { it.value.string == "base" }?.key?.singleOrNull() ?: return null
            var base: Vec3i? = null
            val shape = mutableMapOf<Vec3i, (BlockState) -> Boolean>()
            pat.array("shape")?.let {
                it.reversed().forEachIndexed { y, level ->
                    (level.array ?: return null).forEachIndexed { x, row ->
                        (row.string ?: return null).forEachIndexed { z, c ->
                            val v = Vec3i(x, y, z)
                            if (c == baseKey) {
                                base = v
                            } else if (c != ' ') {
                                shape[v] = key[c] ?: return null
                            }
                        }
                    }
                }
            } ?: return null
            val baseLocation = base ?: return null
            return Pattern(id, shape.map { (it.key - baseLocation) to it.value }.toMap())
        }
    }
}

abstract class MultiBlock(settings: Settings) : Block(settings) {
    abstract val patternIds: Set<Identifier>

    private val patterns: List<Pattern> get() = patternIds.mapNotNull { allPatterns[it] }

    init {
        defaultState = stateManager.defaultState.with(VALID, false)
    }

    override fun appendProperties(stateManager: StateManager.Builder<Block?, BlockState?>) {
        stateManager.add(VALID)
    }

    fun validate(world: World, pos: BlockPos): Boolean {
        if (!world.getBlockState(pos).isOf(this)) return false
        val validPattern = patterns.firstOrNull { it.isValid(pos, world) }
        val valid = validPattern != null && customValidation(world, pos)
        world.setBlockState(pos, world.getBlockState(pos).with(VALID, valid), 3)
        if (valid) {
            onValidate(pos, world, validPattern!!.id)
        } else {
            onInvalidate(pos, world)
        }
        return valid
    }

    open fun customValidation(world: WorldView, pos: BlockPos): Boolean = true

    open fun onValidate(pos: BlockPos, world: World, pattern: Identifier) {}
    open fun onInvalidate(pos: BlockPos, world: World) {}

    companion object {
        val VALID: BooleanProperty = BooleanProperty.of("valid")
    }
}

fun init() {
    ResourceManagerHelper.get(ResourceType.SERVER_DATA)
        .registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId(): Identifier = Identifier(MODID, "patterns")

            override fun reload(manager: ResourceManager) {
                allPatterns.clear()
                for (id in manager.findResources("multiblock-patterns") { it.endsWith(".json") }) {
                    Pattern.load(manager.getResource(id).inputStream)?.let {
                        allPatterns[it.id] = it
                    }
                }
            }
        })
}