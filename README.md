# Straight Tunnel (Fabric, MC 1.21.11, client-side, tên Mojang)

Mod riêng: đào 1 đường hầm thẳng bằng cúp 3x3. Phím **K** bật/tắt (nhóm Misc trong Controls).
Tự đi vòng nước/lava, né bedrock, ăn táo vàng khi máu thấp, mua bình XP sửa cúp (qua /shop, tìm theo tên).

Cấu trúc giống repo autosand: `.github/workflows`, `src/main`, `build.gradle`, `gradle.properties`, `settings.gradle`.
Build trên GitHub Actions (tab Actions -> Build -> tải artifact `straight-tunnel` là file .jar).

Từ khoá tìm trong /shop: đầu `src/main/java/com/straighttunnel/TunnelMiner.java` (`APPLE_NAMES`, `XP_NAMES`, `FOOD_CATEGORY_NAMES`).
