# Rotatable!

Decorate your world how you want to with the power of being able to rotate most* blocks in Adventure mode and finally be able to build inside prefabs without needing to redo all the blocks!

_*Officially the mod aims to just enable rotation of all full blocks in adventure mode, but with the inbuild mod config you are able to control if other blocks should have rotation or even blacklist blocks that the mods shouldnt add rotation to!_


### Key Features
Rotation of full blocks: Pressing "R" while holding a full block like Shale Bricks you are now able to rotate it and place it how you would like!
Adding/removing rotation to any block: With the inbuild config file you can add blocks or blacklist blocks that you want to have or not have rotation

### **Why do i need this?**
By default, Hytale doesnt have an option to rotate most blocks like full blocks, in a normal adventure mode world without needing to switch to creative, so my friend and i hated that structures often spawn in the world with alot of its blocks rotated a different direction than what you can place the exact same block in. so we added the option to do it and now you can finally build how you want!

### **Commands**

`/rotatable` (or `/ra`)
Lists the current mod version.

`/ra count`
Counts the amount of full block types and gives you the number and the number of blocks that is currently being added rotation to. 

`/ra debug`
Outputs a file with the blocks that the mod has added rotation option to.

### **Configuration**

All the mods files are located inside the server's mods directory.
You can modify the mods settings in `mods/rotatable/config.json`

#### Default Configuration:

```
{
  "AutoDetectFullBlocks": true,
  "AutoDetectModdedBlocks": false,
  "PlacementRotationMode": "BlockNormal",
  "IncludedBlocks": [],
  "BlacklistedBlocks": []
}
```

#### Example Configuration:

```
{
  "AutoDetectFullBlocks": true,
  "AutoDetectModdedBlocks": false,
  "PlacementRotationMode": "BlockNormal",
  "IncludedBlocks": ["Ore_*_Shale"],
  "BlacklistedBlocks": ["Soil_*", "Rock_Shale_Brick_Ornate"]
}
```

### **Credits**

This project was created with AI. (Sadly we are not all perfect coders)

**Created by:** Markaabo & Zanodar

**Contributors:** Claude.ai