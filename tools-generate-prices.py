#!/usr/bin/env python3
"""Generate the complete 1.21.11 item catalogue from the official Paper ItemType registry.
No Minecraft binary or plugin JAR is generated. Pass --source to use a downloaded source file.
"""
import argparse, json, re, urllib.request
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path);args=parser.parse_args()
source=args.source.read_text() if args.source else urllib.request.urlopen('https://raw.githubusercontent.com/PaperMC/Paper/ver/1.21.11/paper-api/src/main/java/org/bukkit/inventory/ItemType.java',timeout=30).read().decode()
names=sorted(set(re.findall(r'\b([A-Z][A-Z0-9_]+)\s*=\s*getItemType\(',source)))
if len(names)<1000:raise SystemExit('Registry format changed; refusing incomplete catalogue')
# (effort 1..10, availability 1..10; high=rare, renewability 0/1, farmability 0..10)
# Explicit ordered policy. Every record includes its applied rule and inputs.
rules=[
 ('netherite',r'NETHERITE|ANCIENT_DEBRIS',(10,10,0,1),30000),
 ('unique',r'DRAGON_EGG|ELYTRA|HEAVY_CORE',(10,10,0,1),50000),
 ('rare_loot',r'TOTEM|TRIDENT|MACE|ENCHANTED_GOLDEN_APPLE|NETHER_STAR|HEART_OF_THE_SEA|SPONGE|WITHER_SKELETON_SKULL',(9,8,1,2),10000),
 ('diamond',r'DIAMOND',(8,8,0,2),1000),
 ('gold',r'GOLDEN_|GOLD_|RAW_GOLD',(5,5,1,8),250),
 ('iron',r'IRON_|RAW_IRON|CHAINMAIL',(4,3,1,10),180),
 ('copper',r'COPPER|RAW_COPPER',(3,2,1,8),60),
 ('gems',r'EMERALD|LAPIS|QUARTZ|AMETHYST',(5,4,1,6),180),
 ('mechanism',r'REDSTONE|PISTON|OBSERVER|HOPPER|DISPENSER|DROPPER|REPEATER|COMPARATOR|RAIL',(4,3,1,7),100),
 ('end',r'END_|PURPUR|CHORUS|SHULKER|DRAGON_', (8,7,1,4),600),
 ('nether',r'NETHER|BLAZE|GHAST|MAGMA|WARPED|CRIMSON|SOUL_|BASALT|BLACKSTONE',(6,5,1,5),200),
 ('wood',r'OAK|BIRCH|SPRUCE|JUNGLE|ACACIA|MANGROVE|CHERRY|PALE_OAK|BAMBOO|STICK|WOODEN_', (2,1,1,10),20),
 ('soil_stone',r'DIRT|STONE|SAND|GRAVEL|DEEPSLATE|TUFF|CALCITE|GRANITE|ANDESITE|DIORITE|COBBLE|MUD|CLAY',(2,1,1,9),12),
 ('food_crop',r'WHEAT|CARROT|POTATO|BEETROOT|APPLE|MELON|PUMPKIN|SEEDS|BERRIES|BREAD|COOKED|BEEF|PORK|CHICKEN|MUTTON|COD|SALMON|SUGAR|CACTUS|KELP',(2,1,1,10),18),
 ('fabric',r'WOOL|CARPET|DYE|BANNER|LEATHER|STRING|PAPER',(3,2,1,9),30),
 ('crafted',r'BRICK|GLASS|CONCRETE|TERRACOTTA|CANDLE|POT|BOOK|LANTERN|TORCH|FURNACE|CHEST|BARREL|BOAT|MINECART|BED',(4,3,1,7),60),
 ('default',r'.*',(4,4,1,5),80)]
exclude=re.compile(r'COMMAND|STRUCTURE|SPAWN_EGG$|_BUNDLE$')
excluded={'AIR','BARRIER','LIGHT','DEBUG_STICK','JIGSAW','BEDROCK','END_PORTAL_FRAME','SPAWNER','TRIAL_SPAWNER','VAULT','KNOWLEDGE_BOOK','BUNDLE','REINFORCED_DEEPSLATE','PETRIFIED_OAK_SLAB','TEST_BLOCK','TEST_INSTANCE_BLOCK','PLAYER_HEAD','SUSPICIOUS_SAND','SUSPICIOUS_GRAVEL','FARMLAND','DIRT_PATH','BUDDING_AMETHYST','CHORUS_PLANT'}
blockstate=set(re.findall(r'ItemType\.Typed<BlockStateMeta> ([A-Z0-9_]+) =',source))
result={}
for name in names:
 rule,_,inputs,base=next(r for r in rules if re.search(r[1],name))
 effort,scarcity,renewable,farm=inputs
 score=max(1,effort*3+scarcity*2+(0 if renewable else 10)-farm)
 # Manufacturing bill estimates expressed as multiples of the tier's unit input cost.
 craft=1
 for suffix,factor in {'_BLOCK':9,'_HELMET':5,'_CHESTPLATE':8,'_LEGGINGS':7,'_BOOTS':4,'_PICKAXE':3,'_AXE':3,'_SWORD':2,'_SHOVEL':1,'_HOE':2}.items():
  if name.endswith(suffix):craft=factor;break
 if name.endswith('_SLAB'):base=max(10,base//2)
 if name.endswith('_STAIRS'):base=base*3//2
 base=max(10,score*score*4*craft)
 if name.endswith("_SLAB"):base=max(10,base//2)
 if name.endswith("_STAIRS"):base=base*3//2
 tradable=name not in blockstate and name not in excluded and not exclude.search(name) and not name.endswith('SHULKER_BOX') and name not in {'BEEHIVE','BEE_NEST','DECORATED_POT','CHEST','TRAPPED_CHEST','BARREL','FURNACE','BLAST_FURNACE','SMOKER','DISPENSER','DROPPER','HOPPER','BREWING_STAND','CHISELED_BOOKSHELF','LECTERN','JUKEBOX','CRAFTER'}
 result[name]={'tradable':bool(tradable),'rule':rule,'effort':effort,'scarcity':scarcity,'renewable':bool(renewable),'farmability':farm,'manufacturingUnits':craft,'rarityScore':score,'sellMinor':base,'buyMinor':base//10,'reason':'Regel '+rule+'; gewichteter Aufwand und Verfügbarkeit; Herstellung ×'+str(craft) if tradable else 'Ausgeschlossen: technisch, individuell, geschützter Behälter oder ungeeignet'}
path=Path(__file__).parent/'src/main/resources/prices.json';path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
(Path(__file__).parent/'docs/registry-1.21.11.txt').write_text('\n'.join(names)+'\n')
print(f'{len(result)} items, {sum(x["tradable"] for x in result.values())} tradable -> {path}')
