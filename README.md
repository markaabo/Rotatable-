# Rotatable!

Decorate your world how you want to with the power of being able to rotate most* blocks in Adventure mode and finally be able to build inside prefabs without needing to redo all the blocks!

_*Officially the mod aims to just enable rotation of all full blocks in adventure mode, but with the inbuilt mod config you are able to control if other blocks should have rotation or shouldn't!_

-----
### Key Features
1. **Rotation of full blocks:** Pressing "R" while holding a full block like Shale Bricks you are now able to rotate it and place it how you would like!
2. **Adding/removing rotation to any block:** With the config file you can add any block ID to `IncludedBlocks` or `ExcludedBlocks` that you want to the mod to add/remove rotation to!
3. **Supports modded blocks\*:** We have builtin the ability to enable rotation of virtually any block, either by enabling `AutoDetectModdedBlocks` which will try to detect modded blocks and add rotation to them, or you can add a mods block IDs to `IncludedBlocks` in the config.
4. **Cardinal placement:** Change the block placement behavior to make all included blocks place depending on player facing (This is OFF by default)

_*Always test if it works, we have not currently tested a lot of mods but if any issues come up please report it._

-----
### **Why do I need this?**
By default, Hytale doesn't have an option to rotate most blocks like full blocks, in a normal adventure mode world without needing to switch to creative, so my friend and I hated that structures often spawn in the world, with a lot of its blocks rotated a different direction than what you can place the exact same block in. so we added the option to do it, and now you can finally build how you want!

-----
### **Commands**
* `/rotatable` (or shorthand `/ra`): Lists the current mod version.
* `/ra count`: Counts the amount of full block types and gives you the number and the number of blocks that is currently being added rotation to.
* `/ra debug`: Outputs a file information about settings and other things for debugging the mods behavior. file will be outputted in the mods folder `mods/Rotatable!/debug.txt`

-----
### **Configuration**
All the mods files are located inside the server's mods directory.
You can modify the mods settings in `mods/Rotatable!/config.json`

#### Default Configuration:
```
{
  "ConfigVersion": 1,
  "AutoDetectFullBlocks": true,
  "AutoDetectModdedBlocks": false,
  "CardinalPlacement": false,
  "RotationVariant": "NESW",
  "IncludedBlocks": [],
  "ExcludedBlocks": []
}
```

#### Example Configuration:
```
{
  "ConfigVersion": 1,
  "AutoDetectFullBlocks": true,
  "AutoDetectModdedBlocks": false,
  "CardinalPlacement": false,
  "RotationVariant": "NESW",
  "IncludedBlocks": [
    "Ore_Iron_Shale",
    "Ore_Copper_Stone",
    "Rock_Aqua_*"
  ],
  "ExcludedBlocks": [
    "Rock_Stone_Brick",
    "Rock_Stone_Cobble",
    "Soil_*"
  ]
}
```

* `ConfigVersion` = Revision of the current config, for future reference.
* `AutoDetectFullBlocks` (Default `true`) = Auto detects all vanilla full blocks that doesn't have rotation and makes them Rotatable!. Disable this if you want to manually select blocks to be Rotatable!
* `AutoDetectModdedBlocks` (Default `false`) = Same as `AutoDetectFullBlocks` but for any modded block.
* `CardinalPlacement` (Default `false`) = Enables all Rotatable! block to use facing when placing.
* `RotationVariant`\* (Default `NESW`)= Changes what rotation amount a block gets.
* `IncludedBlocks` = Here you can INCLUDE any block ID you want to also be Rotatable!
* `ExcludedBlocks` = Here you can EXCLUDE any block ID you want to NOT be Rotatable!

_*These options are experimental and is not meant for the normal user to change!_

_Note: Both shown configs are missing all the comments, to get config with comments delete config.json and run game/server with mod enabled, it will then create a new config with all default settings and comments._

-----
### **Known issues**
* **Block placing flicker/zeroing:** Blocks look like they are rotated for a split second when placing them, this is due to an issue on how Hytale works and can only be removed by enabling "Cardinal Placement" in mod settings.
* **Block preview changes direction:** When looking around, R pressed preview changes direction when looking around but block placement doesn't rotate.
-----
### **Credits**
This project was created with help of AI. (Sadly we are not all perfect coders)

**Created by:** Markaabo & Zanodar

**Contributors:** Claude.ai