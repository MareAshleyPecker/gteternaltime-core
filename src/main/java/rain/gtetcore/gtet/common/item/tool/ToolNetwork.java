package rain.gtetcore.gtet.common.item.tool;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import rain.gtetcore.gtet.Gtetcore;

/**
 * 结构工具的网络包 —— 只为「客户端滚轮切模式 → 服务端改 NBT」这一件事存在。
 *
 * @author rain fox
 */
public final class ToolNetwork {

    private static final String VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(Gtetcore.id("tool_mode"))
            .networkProtocolVersion(() -> VERSION)
            .clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals)
            .simpleChannel();

    private ToolNetwork() {}

    /** 在 mod 构造阶段调用一次（客户端/服务端都要）。 */
    public static void register() {
        CHANNEL.messageBuilder(CycleModePacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CycleModePacket::encode)
                .decoder(CycleModePacket::decode)
                .consumerMainThread(CycleModePacket::handle)
                .add();
    }

    /** 客户端请求切换模式。 */
    public static void sendCycle(int direction) {
        CHANNEL.sendToServer(new CycleModePacket(direction));
    }

    /** 切换请求：direction > 0 向后，< 0 向前。 */
    public static final class CycleModePacket {

        private final int direction;

        public CycleModePacket(int direction) {
            this.direction = direction;
        }

        public static void encode(CycleModePacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.direction);
        }

        public static CycleModePacket decode(FriendlyByteBuf buffer) {
            return new CycleModePacket(buffer.readVarInt());
        }

        public static void handle(CycleModePacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> context) {
            var ctx = context.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null) return;
                ItemStack stack = player.getMainHandItem();
                if (!StructureToolBehavior.isStructureTool(stack)) return;
                StructureToolBehavior.WorkMode mode =
                        StructureToolBehavior.cycleMode(stack, packet.direction);
                StructureToolBehavior.notifyMode(player, mode);
            });
            ctx.setPacketHandled(true);
        }
    }
}
