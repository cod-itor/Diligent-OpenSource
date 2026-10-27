plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_lua")
    dynamicDelivery { deliveryType.set("on-demand") }
}
