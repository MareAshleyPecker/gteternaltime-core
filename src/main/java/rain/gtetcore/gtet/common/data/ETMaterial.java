
package rain.gtetcore.gtet.common.data;

import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.common.data.GTMaterials;
import kotlin.reflect.jvm.internal.impl.descriptors.Visibilities;
import rain.gtetcore.gtet.common.data.material.ETElementMaterials;

/**
 * @see ETElementMaterials
 */
public class ETMaterial {

    public static Material MaterialNAME = GTMaterials.Iron;
    public static Material Q235;
    public static Material Q345;
    public static Material Q420C;
    public static Material GFRP;
    public static Material Resin;
    public static Material GlassFiber;
    public static Material Al2O3;
    public static Material CaO;
    public static Material Na2O;
    public static Material K2O;

    public static void init() {}

}
