package io.github.bluepython508.libsimplemultiblock

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.fabricmc.fabric.api.tag.TagRegistry
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceType
import net.minecraft.state.StateManager
import net.minecraft.state.property.BooleanProperty
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3i
import net.minecraft.util.registry.Registry
import net.minecraft.world.World
import net.minecraft.world.WorldView
import java.io.InputStream

internal const val MODID = "libsimplemultiblock"

internal fun JsonObject.ident(s: String): Identifier? = string(s)?.let(Identifier::tryParse)

internal operator fun Vec3i.minus(other: Vec3i): Vec3i = Vec3i(x - other.x, y - other.y, z - other.z)

internal val allPatterns: MutableMap<Identifier, Pattern> = mutableMapOf()

internal fun matches(it: Any): ((BlockState) -> Boolean)? {
    if (it is String) {
        if (it == "base") {
            return { true }
        }
    } else if (it is JsonObject) {
        return it.ident("block")?.let(Registry.BLOCK::get)
            ?.let { block -> { state: BlockState -> state.isOf(block) } }
            ?: it.ident("tag")?.let(TagRegistry::block)
                ?.let { tag -> { state: BlockState -> state.isIn(tag) } }
    }
    return null
}

internal class Pattern(private val pattern: Map<Vec3i, (BlockState) -> Boolean>) {
    fun isValid(base: BlockPos, world: WorldView): Boolean =
        pattern.entries.all {
            it.value(world.getBlockState(base.add(it.key)))
        }

    companion object {
        fun load(stream: InputStream): Pair<Identifier, Pattern>? {
            val pat = Parser.default().parse(stream) as JsonObject
            val id = pat.ident("id") ?: return null
            val key = pat.obj("key")?.map {
                (it.key.singleOrNull()?.takeUnless { c -> c == ' ' } ?: return null) to (it.value?.let(::matches)
                    ?: return null)
            }?.toMap() ?: return null
            val baseKey = pat.obj("key")?.entries?.find { it.value == "base" }?.key?.singleOrNull() ?: return null
            var base: Vec3i? = null
            val shape = mutableMapOf<Vec3i, (BlockState) -> Boolean>()
            pat.array<JsonArray<String>>("shape")?.let {
                it.reversed().forEachIndexed { y, level ->
                    level.forEachIndexed { x, row ->
                        row.forEachIndexed { z, c ->
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
            return id to Pattern(shape.map { (it.key - baseLocation) to it.value }.toMap())
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
        val valid = patterns.any { it.isValid(pos, world) } && customValidation(world, pos)
        world.setBlockState(pos, world.getBlockState(pos).with(VALID, valid), 3)
        return valid
    }

    open fun customValidation(world: WorldView, pos: BlockPos): Boolean = true

    companion object {
        val VALID: BooleanProperty = BooleanProperty.of("valid")
    }
}

fun init() {
    ResourceManagerHelper.get(ResourceType.SERVER_DATA)
        .registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId(): Identifier = Identifier(MODID, "patterns")

            override fun apply(manager: ResourceManager) {
                allPatterns.clear()
                for (id in manager.findResources("multiblock-patterns") { it.endsWith(".json") }) {
                    Pattern.load(manager.getResource(id).inputStream)?.let {
                        allPatterns[it.first] = it.second
                    }
                }
            }
        })
}