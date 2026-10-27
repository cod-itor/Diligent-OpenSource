plugins { id("com.android.asset-pack") }
assetPack {
    packName.set("toolchain_elixir")
    dynamicDelivery { deliveryType.set("on-demand") }
}
