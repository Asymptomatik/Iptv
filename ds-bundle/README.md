# IptvApp — Design System « Cinematic Glass »

Direction visuelle V2 pour IptvApp (Android · Jetpack Compose + Compose for TV).
Remplace le clone sombre/rouge Netflix par un langage **premium, verre dépoli, accent gradient violet→cyan**.

## Principes
- **Fond** near-black violet-tinté (`#0A0A0F`) avec halos gradient ambiants.
- **Surfaces verre dépoli** (blur + translucidité + bordure subtile) pour cards, barres, feuilles.
- **Accent gradient violet→cyan** (`#8B5CF6 → #22D3EE`) sur actions, focus, états actifs — plus de rouge Netflix.
- **Focus TV** repensé : lift + scale + anneau gradient + glow (remplace la bordure blanche).
- **Rayons doux** (cards 20px, feuilles 28px, boutons pill).
- **Dual form factor** : mobile tactile (tab bar flottante) + Android TV (rail latéral, focus D-pad).

## Contenu
- `styles.css` — **source de vérité des tokens** (couleurs, typo, rayons, espacements, verre, ombres). Transposé 1:1 vers le thème Compose.
- `components/foundations/` — couleurs, typographie, surfaces/rayons.
- `components/components/` — boutons, chips, poster card (états), rangée, champ, navigation, hero.
- `components/screens-mobile/` — accueil, recherche, onboarding, profils.
- `components/screens-tv/` — accueil, détail Film/Série/Live+EPG, lecteur.

## Après validation
Les tokens et composants sont transposés dans `app/src/main/kotlin/.../ui/theme` puis `ui/components` et `ui/screen` (Phase B), sans changement de logique.
