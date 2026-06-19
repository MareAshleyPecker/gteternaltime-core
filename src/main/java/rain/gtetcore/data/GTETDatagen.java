package rain.gtetcore.data;

import net.minecraft.data.DataGenerator;
import net.minecraftforge.common.data.LanguageProvider;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import rain.gtetcore.Gtetcore;
import rain.gtetcore.data.lang.BlockLang;

import java.util.HashMap;
import java.util.Map;


@Mod.EventBusSubscriber(modid = Gtetcore.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class GTETDatagen {

	public static class UnifiedLanguageProvider extends LanguageProvider {

		private final Map<String, Map<String, String>> translations = new HashMap<> ();

		private final String locale;

		public UnifiedLanguageProvider(DataGenerator gen, String locale) {
			super(gen.getPackOutput(), Gtetcore.MODID, locale);
			this.locale = locale;
			initializeTranslations();
		}

		@Override
		protected void addTranslations() {
			translations.forEach((key, langMap) -> {
				String translation = langMap.get(locale);
				if (translation != null) {
					add(key, translation);
				}
			});
		}

		private void initializeTranslations() {
			BlockLang.init(this);
		}

		public void add2L(String key, String en, String zh) {
			Map<String, String> CNENlangMap = new HashMap<>();
			CNENlangMap.put("en_us", en);
			CNENlangMap.put("zh_cn", zh);
			translations.put(key, CNENlangMap);
		}

	}

	@SubscribeEvent
	public static void gatherData(GatherDataEvent event) {
		DataGenerator generator = event.getGenerator();
		// ExistingFileHelper existingFileHelper = event.getExistingFileHelper();
		generator.addProvider(event.includeClient(), new UnifiedLanguageProvider(generator, "en_us"));
		generator.addProvider(event.includeClient(), new UnifiedLanguageProvider(generator, "zh_cn"));
	}

}
