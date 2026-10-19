package fr.demo.caisse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

enum class ThemeMode(val libelle: String) { CLAIR("Clair"), SOMBRE("Sombre"), SYSTEME("Auto") }

/** Couleurs sémantiques absentes de Material 3 : succès, alerte, information, barre d'outils. */
@Immutable
class CouleursExt(
    val succes: Color,
    val succesFond: Color,
    val alerte: Color,
    val alerteFond: Color,
    val info: Color,
    val infoFond: Color,
    val surfaceHaute: Color,
    val barre: Color,
    val surBarre: Color,
    val surBarreDiscret: Color,
)

private val ExtClair = CouleursExt(
    succes = Color(0xFF15803D), succesFond = Color(0xFFDCFCE7),
    alerte = Color(0xFFB45309), alerteFond = Color(0xFFFEF3C7),
    info = Color(0xFF0369A1), infoFond = Color(0xFFE0F2FE),
    surfaceHaute = Color(0xFFF8FAFC),
    barre = Color(0xFF0F2A2E), surBarre = Color.White, surBarreDiscret = Color(0xFF9CC5C0),
)
private val ExtSombre = CouleursExt(
    succes = Color(0xFF4ADE80), succesFond = Color(0xFF14532D),
    alerte = Color(0xFFFBBF24), alerteFond = Color(0xFF451A03),
    info = Color(0xFF38BDF8), infoFond = Color(0xFF082F49),
    surfaceHaute = Color(0xFF16213A),
    barre = Color(0xFF07161A), surBarre = Color(0xFFE6F4F2), surBarreDiscret = Color(0xFF7FB0AA),
)

private val SchemaClair: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF475569), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EAF0), onSecondaryContainer = Color(0xFF0F172A),
    background = Color(0xFFEEF2F5), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EDF2), onSurfaceVariant = Color(0xFF53606F),
    outline = Color(0xFFC9D2DC), outlineVariant = Color(0xFFE1E7EE),
    error = Color(0xFFC62828), onError = Color.White, errorContainer = Color(0xFFFDE4E4), onErrorContainer = Color(0xFF7F1D1D),
)

private val SchemaSombre: ColorScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF9AA8B8), onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF243447), onSecondaryContainer = Color(0xFFE2E8F0),
    background = Color(0xFF0C141B), onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF131E28), onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2A37), onSurfaceVariant = Color(0xFF9AA8B8),
    outline = Color(0xFF34475A), outlineVariant = Color(0xFF243447),
    error = Color(0xFFF87171), onError = Color(0xFF2A0A0A), errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFECACA),
)

val LocalCouleursExt = staticCompositionLocalOf { ExtClair }

/** Accès pratique : `Ext.succes`, `Ext.alerteFond`… */
object Ext {
    val succes: Color @Composable get() = LocalCouleursExt.current.succes
    val succesFond: Color @Composable get() = LocalCouleursExt.current.succesFond
    val alerte: Color @Composable get() = LocalCouleursExt.current.alerte
    val alerteFond: Color @Composable get() = LocalCouleursExt.current.alerteFond
    val info: Color @Composable get() = LocalCouleursExt.current.info
    val infoFond: Color @Composable get() = LocalCouleursExt.current.infoFond
    val surfaceHaute: Color @Composable get() = LocalCouleursExt.current.surfaceHaute
    val barre: Color @Composable get() = LocalCouleursExt.current.barre
    val surBarre: Color @Composable get() = LocalCouleursExt.current.surBarre
    val surBarreDiscret: Color @Composable get() = LocalCouleursExt.current.surBarreDiscret
}

private val AccentsCategories = listOf(
    Color(0xFF0E7490), Color(0xFF15803D), Color(0xFFB45309), Color(0xFFB91C1C),
    Color(0xFF6D28D9), Color(0xFFBE185D), Color(0xFF0F766E), Color(0xFFC2410C),
)

/** Couleur d'accent stable pour une catégorie (même nom = même couleur, quel que soit l'ordre). */
fun accentCategorie(categorie: String): Color = AccentsCategories[categorie.sumOf { it.code } % AccentsCategories.size]

/**
 * Police de l'application : Inter (licence SIL OFL, fichiers dans src/main/resources/fonts). Dessinée pour les écrans : chiffres nets et
 * réguliers (prix, codes PIN), lettres ouvertes, lisible de loin et au doigt. Si un fichier manque, retour à la police du système.
 */
val PoliceCaisse: FontFamily = runCatching {
    fun f(nom: String, graisse: FontWeight): Font {
        val octets = PoliceCaisseMarqueur::class.java.getResourceAsStream("/fonts/$nom.ttf")!!.use { it.readBytes() }
        return androidx.compose.ui.text.platform.Font(identity = nom, data = octets, weight = graisse)
    }
    FontFamily(
        f("Inter-Light", FontWeight.Light), f("Inter-Regular", FontWeight.Normal), f("Inter-Medium", FontWeight.Medium),
        f("Inter-SemiBold", FontWeight.SemiBold), f("Inter-Bold", FontWeight.Bold), f("Inter-Black", FontWeight.Black),
    )
}.getOrDefault(FontFamily.Default)

private object PoliceCaisseMarqueur

private fun Typography.avecPolice(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val sombre = when (mode) {
        ThemeMode.CLAIR -> false
        ThemeMode.SOMBRE -> true
        ThemeMode.SYSTEME -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalCouleursExt provides if (sombre) ExtSombre else ExtClair) {
        MaterialTheme(colorScheme = if (sombre) SchemaSombre else SchemaClair, typography = Typography().avecPolice(PoliceCaisse)) {
            // Surface racine : fixe la couleur de texte par défaut (sinon noir sur fond sombre)
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                // Texte courant (hors composants Material) : même police partout
                ProvideTextStyle(TextStyle(fontFamily = PoliceCaisse), content = content)
            }
        }
    }
}
