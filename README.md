# LibSimpleMultiblock

## Setup

0. Add jitpack to the repositories block in `build.gradle`
   ```groovy
      maven {
        url = "https://jitpack.io"
      }
   ```

1. Add a dependency on this library
   ```groovy
      modImplementation "com.github.bluepython508:LibSimpleMultiblock:${project.multiblock_version}"
      include "com.github.bluepython508:LibSimpleMultiblock:${project.multiblock_version}"
   ```
   And in `gradle.properties`:
   ```
      multiblock_version=1.0.0
   ```
2. Have your multiblock's core block class extend `io.github.bluepython508.libsimplemultiblock.MultiBlock`
   Place a JSON file in `{your mod's datapack}/multiblock-patterns/` similar to the following
   ```json5
      {
        "id": "{your multiblock id}", // All IDs returned by the `getPatternIds` will be looked for in this field
        "key": {
          "B": "base", // A single base is required, no more than 1 is permitted
          "T": {"block":  "{a block id here}"},
          "A": {"tag":  "{a tag id here}"}
        },
        // Operates similarly to shaped crafting, but in 3 dimensions
        "shape": [ // The top list goes down y-levels, the next in increasing x-coords - Symmetry is recommended
          [
            "TAT",
            "ABA",
            "TAT"
          ]    
        ]    
      }
   ```
3. Then, call `validate` with a world and the position of the core block, and it will return whether the multiblock is
   valid. It will also set the `MultiBlock.VALID` field on the blockstate.

## License

This library is available under the GPL v3 license.
