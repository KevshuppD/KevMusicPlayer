# Sistema de Estilo Visual: KevMusicPlayer

Este documento detalla la guía de diseño y el sistema de estilo visual utilizado en **KevMusicPlayer**. Su objetivo es servir como referencia para mantener la consistencia estética y facilitar la réplica de este estilo visual premium en otros proyectos.

---

## 1. Filosofía de Diseño

El estilo de **KevMusicPlayer** se define como **Neo-Glow Premium / Glassmorphism**. Se caracteriza por:
- **Fondos ultra oscuros**: Prioriza el ahorro de batería en pantallas OLED y crea un contraste dramático con los colores primarios.
- **Detalles luminiscentes (Glow/Neon)**: Uso de colores primarios y secundarios de alta saturación y brillo eléctrico.
- **Capas translúcidas (Efecto Cristal)**: Tarjetas y diálogos con fondos semi-transparentes y bordes sutiles para crear jerarquía y profundidad visual.
- **Microinteracciones dinámicas**: Respuestas táctiles y transiciones sumamente suaves (optimizadas para pantallas de 120Hz).

---

## 2. Paletas de Colores (Temas)

La aplicación soporta 5 temas visuales dinámicos. Cada uno de ellos está configurado usando `ColorScheme` en Compose:

### A. Tema Cyberpunk (Predeterminado / Dark)
Inspirado en la estética retro-futurista de neón.
- **Primary**: `#FFFF4081` (Rosa/Rosa Neón Vibrante)
- **Secondary**: `#B388FF` (Violeta Luminiscente)
- **Tertiary**: `#18FFFF` (Cian Eléctrico)
- **Background**: `#08090F` (Negro Espacial Profundo)
- **Surface**: `#121422` (Azul Pizarra Muy Oscuro)
- **Card Background**: `#1A1C2E` (Azul/Morado Pizarra Intermedio)
- **On Background**: `#F0F1FA` / **On Surface**: `#E1E2EC`

### B. Tema Azul Petróleo (Petrol)
Un tono más sobrio y tecnológico, ideal para el uso nocturno relajado.
- **Primary**: `#00E5FF` (Cian/Turquesa Neón)
- **Secondary**: `#80F1FF` (Cian Suave)
- **Background**: `#040B0E` (Negro verdoso)
- **Surface**: `#0A1E24` (Azul petróleo oscuro)
- **Surface Variant**: `#122C34`
- **On Primary**: `#00363C`

### C. Tema Turquesa (Turquoise)
Un estilo fresco, orgánico y enérgico.
- **Primary**: `#00F5D4` (Verde Turquesa Eléctrico)
- **Secondary**: `#8FFFEF` (Turquesa Pálido)
- **Background**: `#020E0C` (Negro Bosque Profundo)
- **Surface**: `#071F1B` (Jade Oscuro)
- **Surface Variant**: `#0D322C`

### D. Tema Obsidiana (Obsidian / OLED Black)
Diseñado específicamente para pantallas AMOLED, maximizando el negro puro y reduciendo brillos innecesarios.
- **Primary**: `#FFFFFF` (Blanco Puro)
- **Secondary**: `#B0B3C6` (Plata Satinado)
- **Background**: `#000000` (Negro Puro OLED)
- **Surface**: `#0E0E0E` (Gris Obsidiana Extra Oscuro)
- **Surface Variant**: `#1C1C1C`

### E. Tema Monocromo (Monochrome / Light Mode)
Un diseño limpio, minimalista, de alto contraste en escala de grises.
- **Primary**: `#000000` (Negro)
- **Secondary**: `#555555` (Gris Oscuro)
- **Background**: `#FFFFFF` (Blanco Puro)
- **Surface**: `#F6F6F6` (Gris Claro)
- **Surface Variant**: `#EEEEEE`

---

## 3. Tipografía y Escala de Texto

La jerarquía del texto utiliza el sistema de tipografía Material Design 3 adaptado para legibilidad en interfaces de música:

| Estilo de Texto | Peso de Fuente (Weight) | Tamaño (Size) | Uso Recomendado |
| :--- | :--- | :--- | :--- |
| **Headline Large** | ExtraBold / Black | `24.sp` a `32.sp` | Título de la canción en reproductor, títulos principales |
| **Title Medium** | Bold / Medium | `16.sp` a `20.sp` | Título de la canción en listas, nombres de artistas |
| **Body Large** | Normal / Medium | `16.sp` | Letras de canciones traduciéndose, textos descriptivos |
| **Body Medium** | Normal | `14.sp` | Subtítulos, carpetas de origen, metadatos secundarios |
| **Label Small** | Medium | `11.sp` | Contadores de tiempo, badges, etiquetas informativas |

---

## 4. Estructuras, Formas y Espaciados

### Bordes y Redondeados (Shapes)
- **Paneles y Letras Contenedoras**: Redondeado ultra-pronunciado de **`32.dp`** (`RoundedCornerShape(32.dp)`) con un fondo oscuro translúcido (`Color.Black.copy(alpha = 0.75f)`) para generar el efecto flotante sobre el fondo.
- **Tarjetas e Ítems de Listas**: Redondeado medio de **`16.dp`** o **`20.dp`** para encajar con el diseño moderno.
- **Botones y Controles**: Diseños circulares (`CircleShape`) o redondeados suaves de **`12.dp`** a **`16.dp`**.

### Espaciados y Márgenes
- **Padding General de Pantalla**: Márgenes laterales holgados de **`24.dp`** a **`28.dp`** para evitar la fatiga visual y dar aire a la interfaz.
- **Separación de Ítems en Listas**: Separación generosa de **`28.dp`** en componentes interactivos complejos (como la lista de letras) y de **`12.dp`** a **`16.dp``** en listas de canciones ordinarias.

---

## 5. Microanimaciones y Transiciones

### Comportamiento de Letras Deslizantes (`ScrollingLyricsView`):
- **Efecto de Zoom y Foco Activo**: 
  - La línea de letra activa adquiere una escala de **`1.05x`** (`scaleX = 1.05f`, `scaleY = 1.05f`) y opacidad total (`alpha = 1f`).
  - Las líneas inactivas disminuyen gradualmente su opacidad basándose en la distancia a la línea activa:
    ```kotlin
    alpha = (0.4f - (distance * 0.05f)).coerceAtLeast(0.1f)
    ```
- **Desplazamiento Dinámico**: Movimiento fluido centrado mediante `animateScrollToItem(activeIndex)`.

### Transición de Pantallas:
- La navegación incluye un control de rendimiento para desactivar animaciones si el usuario lo prefiere (`disable_animations` -> `LocalDisableAnimations`).
- Cuando están activadas, se aplican transiciones de desvanecimiento (`fadeIn` y `fadeOut` de `300ms`) en lugar de deslizamientos bruscos.

---

## 6. Componentes Premium Adicionales

- **Ecualizador Visual (FFT Visualizer)**: 
  - Renderiza **20 bandas de frecuencias** de audio dinámicas.
  - Si el usuario no concede permisos de grabación de audio, se activa una simulación animada basada en ondas senoidales matemáticas (`Math.sin()`) combinada con un factor de ruido aleatorio para simular actividad orgánica.
- **Oscurecimiento de Fondo Dinámico**:
  - Las áreas de visualización flotantes aplican contrastes oscuros con transparencias (`alpha = 0.3f` a `0.75f`) sobre los fondos de color de la paleta activa para simular superposición de cristales oscuros.
