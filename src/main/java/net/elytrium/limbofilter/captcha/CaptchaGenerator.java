/*
 * Copyright (C) 2021 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limbofilter.captcha;

import com.google.common.primitives.Floats;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import it.unimi.dsi.fastutil.Pair;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.elytrium.limboapi.api.protocol.packets.data.MapData;
import net.elytrium.limboapi.api.protocol.packets.data.MapPalette;
import net.elytrium.limbofilter.LimboFilter;
import net.elytrium.limbofilter.Settings;
import net.elytrium.limbofilter.cache.captcha.CachedCaptcha;
import net.elytrium.limbofilter.captcha.map.CraftMapCanvas;
import net.elytrium.limbofilter.captcha.painter.CaptchaPainter;
import net.elytrium.limbofilter.captcha.painter.RenderedFont;

public class CaptchaGenerator {

  private final CaptchaPainter painter;
  private final List<CraftMapCanvas> preparedBackplates = new ArrayList<>();
  private final List<Path> backplateFiles = new ArrayList<>();
  private final Map<Path, CraftMapCanvas> backplateCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75F, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Path, CraftMapCanvas> eldest) {
      return this.size() > Math.max(1, Settings.IMP.MAIN.CAPTCHA_GENERATOR.BACKPLATE_CACHE_SIZE);
    }
  });
  private final List<RenderedFont> fonts = new LinkedList<>();
  private final List<byte[]> colors = new LinkedList<>();
  private final LimboFilter plugin;

  private ThreadPoolExecutor executor;
  private boolean shouldStop;
  private CachedCaptcha cachedCaptcha;
  private CachedCaptcha tempCachedCaptcha;
  private ThreadLocal<Iterator<CraftMapCanvas>> preparedBackplatesIterator;
  private ThreadLocal<Iterator<RenderedFont>> fontIterator;
  private ThreadLocal<Iterator<byte[]>> colorIterator;
  private final AtomicInteger datasetSequence = new AtomicInteger();
  private final AtomicInteger datasetTrainCounter = new AtomicInteger();
  private final AtomicInteger datasetValidationCounter = new AtomicInteger();
  private final Object datasetLock = new Object();
  private Path datasetTrainPath;
  private Path datasetValidationPath;
  private Writer trainLabels;
  private Writer validationLabels;

  public CaptchaGenerator(LimboFilter plugin) {
    this.plugin = plugin;
    if (Settings.IMP.MAIN.FRAMED_CAPTCHA.FRAMED_CAPTCHA_ENABLED) {
      this.painter = new CaptchaPainter(
          MapData.MAP_DIM_SIZE * Settings.IMP.MAIN.FRAMED_CAPTCHA.WIDTH,
          MapData.MAP_DIM_SIZE * Settings.IMP.MAIN.FRAMED_CAPTCHA.HEIGHT);
    } else {
      this.painter = new CaptchaPainter(MapData.MAP_DIM_SIZE, MapData.MAP_DIM_SIZE);
    }
  }

  public void initializeGenerator() {
    this.preparedBackplates.clear();
    this.backplateFiles.clear();
    this.backplateCache.clear();
    this.closeDatasetWriters();

    try {
      for (String backplatePath : Settings.IMP.MAIN.CAPTCHA_GENERATOR.BACKPLATE_PATHS) {
        if (!backplatePath.isEmpty()) {
          CraftMapCanvas craftMapCanvas = this.createCraftMapCanvas();
          craftMapCanvas.drawImage(this.resizeIfNeeded(ImageIO.read(this.plugin.getFile(backplatePath)),
              this.painter.getWidth(), this.painter.getHeight()), this.painter.getWidth(), this.painter.getHeight());
          this.preparedBackplates.add(craftMapCanvas);
        }
      }

      for (String backplateDirectory : Settings.IMP.MAIN.CAPTCHA_GENERATOR.BACKPLATE_DIRECTORIES) {
        if (!backplateDirectory.isEmpty()) {
          this.loadBackplatesFromDirectory(backplateDirectory);
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }

    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.SAVE_NUMBER_SPELLING_OUTPUT) {
      int from = (int) Math.pow(10, Settings.IMP.MAIN.CAPTCHA_GENERATOR.LENGTH - 1);
      int to = from * 10;

      try (OutputStream output = new FileOutputStream("number_spelling.txt")) {
        for (int i = from; i < to; i++) {
          String result = this.spellNumber(i);
          output.write(String.format("%d %s%s", i, result, System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    }

    this.fonts.clear();

    float fontSize = (float) Settings.IMP.MAIN.CAPTCHA_GENERATOR.RENDER_FONT_SIZE;

    if (Settings.IMP.MAIN.FRAMED_CAPTCHA.FRAMED_CAPTCHA_ENABLED && Settings.IMP.MAIN.FRAMED_CAPTCHA.AUTOSCALE_FONT) {
      fontSize *= Math.min(Settings.IMP.MAIN.FRAMED_CAPTCHA.WIDTH, Settings.IMP.MAIN.FRAMED_CAPTCHA.HEIGHT);
    }

    Map<TextAttribute, Object> textSettings = Map.of(
        TextAttribute.SIZE,
        fontSize,
        TextAttribute.STRIKETHROUGH,
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.STRIKETHROUGH,
        TextAttribute.UNDERLINE,
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.UNDERLINE
    );

    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.USE_STANDARD_FONTS) {
      this.fonts.add(this.getRenderedFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) fontSize).deriveFont(textSettings)));
      this.fonts.add(this.getRenderedFont(new Font(Font.SERIF, Font.PLAIN, (int) fontSize).deriveFont(textSettings)));
      this.fonts.add(this.getRenderedFont(new Font(Font.MONOSPACED, Font.PLAIN, (int) fontSize).deriveFont(textSettings)));
    }

    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONTS_PATH != null) {
      Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONTS_PATH.forEach(fontFile -> this.loadFont(fontFile, textSettings));
    }

    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONTS_DIRECTORIES != null) {
      Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONTS_DIRECTORIES.forEach(fontDirectory -> {
        if (!fontDirectory.isEmpty()) {
          this.loadFontsFromDirectory(fontDirectory, textSettings);
        }
      });
    }

    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.GRADIENT.GRADIENT_ENABLED) {
      BufferedImage gradientImage = new BufferedImage(this.painter.getWidth(), this.painter.getHeight(), BufferedImage.TYPE_INT_RGB);
      int[] imageData = ((DataBufferInt) gradientImage.getRaster().getDataBuffer()).getData();
      Graphics2D graphics = gradientImage.createGraphics();

      ThreadLocalRandom random = ThreadLocalRandom.current();
      Settings.MAIN.CAPTCHA_GENERATOR.GRADIENT settings = Settings.IMP.MAIN.CAPTCHA_GENERATOR.GRADIENT;

      Color[] colors = Settings.IMP.MAIN.CAPTCHA_GENERATOR.RGB_COLOR_LIST.stream().map(s -> Color.decode("#" + s)).toArray(Color[]::new);

      List<Double> fractions = settings.FRACTIONS;

      if (fractions == null || fractions.isEmpty()) {
        double step = 1.0 / colors.length;
        fractions = IntStream.range(0, colors.length).mapToDouble(i -> i * step).boxed().collect(Collectors.toList());
      }

      if (colors.length != fractions.size()) {
        throw new IllegalStateException("The color list and fraction list must contain the same number of elements");
      }

      for (int i = 0; i < settings.GRADIENTS_COUNT; ++i) {
        LinearGradientPaint paint = new LinearGradientPaint(
            (float) settings.START_X + random.nextFloat() * (float) settings.START_X_RANDOMNESS * this.painter.getWidth(),
            (float) settings.START_Y + random.nextFloat() * (float) settings.START_Y_RANDOMNESS * this.painter.getHeight(),
            (float) settings.END_X - random.nextFloat() * (float) settings.END_X_RANDOMNESS * this.painter.getWidth(),
            (float) settings.END_Y - random.nextFloat() * (float) settings.END_Y_RANDOMNESS * this.painter.getHeight(),
            Floats.toArray(fractions), colors);

        graphics.setPaint(paint);
        graphics.fillRect(0, 0, gradientImage.getWidth(), gradientImage.getHeight());

        this.colors.add(MapPalette.imageToBytes(imageData,
            new byte[this.painter.getWidth() * this.painter.getHeight()],
            ProtocolVersion.MAXIMUM_VERSION));
      }

      graphics.dispose();
    } else {
      Settings.IMP.MAIN.CAPTCHA_GENERATOR.RGB_COLOR_LIST.forEach(e ->
          this.colors.add(new byte[]{MapPalette.tryFastMatchColor(Integer.parseInt(e, 16) | 0xFF000000, ProtocolVersion.MAXIMUM_VERSION)}));
    }

    this.preparedBackplatesIterator = ThreadLocal.withInitial(this.preparedBackplates::listIterator);
    this.fontIterator = ThreadLocal.withInitial(this.fonts::listIterator);
    this.colorIterator = ThreadLocal.withInitial(this.colors::listIterator);
    this.initializeDatasetExport();
  }

  private CraftMapCanvas createCraftMapCanvas() {
    if (Settings.IMP.MAIN.FRAMED_CAPTCHA.FRAMED_CAPTCHA_ENABLED) {
      return new CraftMapCanvas(Settings.IMP.MAIN.FRAMED_CAPTCHA.WIDTH, Settings.IMP.MAIN.FRAMED_CAPTCHA.HEIGHT);
    } else {
      return new CraftMapCanvas(1, 1);
    }
  }

  private void loadBackplatesFromDirectory(String directory) {
    try (Stream<Path> files = Files.list(this.plugin.getFile(directory).toPath())) {
      int before = this.backplateFiles.size();
      files.filter(Files::isRegularFile)
          .filter(this::isImageFile)
          .forEach(this.backplateFiles::add);
      LimboFilter.getLogger().info("Discovered {} backplates in directory {}", this.backplateFiles.size() - before, directory);
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private boolean isImageFile(Path path) {
    String fileName = path.getFileName().toString().toLowerCase();
    return fileName.endsWith(".png")
        || fileName.endsWith(".jpg")
        || fileName.endsWith(".jpeg")
        || fileName.endsWith(".bmp")
        || fileName.endsWith(".gif")
        || fileName.endsWith(".webp");
  }

  private CraftMapCanvas loadBackplate(Path path) {
    try {
      CraftMapCanvas craftMapCanvas = this.createCraftMapCanvas();
      craftMapCanvas.drawImage(this.resizeIfNeeded(ImageIO.read(path.toFile()),
          this.painter.getWidth(), this.painter.getHeight()), this.painter.getWidth(), this.painter.getHeight());
      return craftMapCanvas;
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private void loadFontsFromDirectory(String directory, Map<TextAttribute, Object> textSettings) {
    try (Stream<Path> files = Files.list(this.plugin.getFile(directory).toPath())) {
      files.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".ttf"))
          .forEach(path -> this.loadFont(path.toString(), textSettings));
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private void loadFont(String fontPath, Map<TextAttribute, Object> textSettings) {
    try {
      if (!fontPath.isEmpty()) {
        LimboFilter.getLogger().info("Loading font {}.", fontPath);
        Font font = Font.createFont(Font.TRUETYPE_FONT, this.plugin.getFile(fontPath));
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        this.fonts.add(this.getRenderedFont(font.deriveFont(textSettings)));
      }
    } catch (FontFormatException | IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private void initializeDatasetExport() {
    if (!Settings.IMP.MAIN.CAPTCHA_GENERATOR.SAVE_CAPTCHA_DATASET) {
      return;
    }

    try {
      Path root = Path.of(Settings.IMP.MAIN.CAPTCHA_GENERATOR.CAPTCHA_DATASET_PATH);
      this.datasetTrainPath = root.resolve("train");
      this.datasetValidationPath = root.resolve("validation");
      Files.createDirectories(this.datasetTrainPath);
      Files.createDirectories(this.datasetValidationPath);

      this.trainLabels = new FileWriter(this.datasetTrainPath.resolve("labels.csv").toFile(), StandardCharsets.UTF_8, false);
      this.validationLabels = new FileWriter(this.datasetValidationPath.resolve("labels.csv").toFile(), StandardCharsets.UTF_8, false);
      this.trainLabels.write("file,label" + System.lineSeparator());
      this.validationLabels.write("file,label" + System.lineSeparator());
      this.datasetSequence.set(0);
      this.datasetTrainCounter.set(0);
      this.datasetValidationCounter.set(0);
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private RenderedFont getRenderedFont(Font font) {
    boolean scaleFont = Settings.IMP.MAIN.FRAMED_CAPTCHA.FRAMED_CAPTCHA_ENABLED && Settings.IMP.MAIN.FRAMED_CAPTCHA.AUTOSCALE_FONT;
    int multiplierX = scaleFont ? Settings.IMP.MAIN.FRAMED_CAPTCHA.WIDTH : 1;
    int multiplierY = scaleFont ? Settings.IMP.MAIN.FRAMED_CAPTCHA.HEIGHT : 1;

    return new RenderedFont(font,
        new FontRenderContext(null, true, true),
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.PATTERN.toCharArray(),
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONT_LETTER_WIDTH * multiplierX,
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONT_LETTER_HEIGHT * multiplierY,
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONT_OUTLINE,
        (float) Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONT_OUTLINE_RATE,
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONT_OUTLINE_OFFSET_X * multiplierX,
        Settings.IMP.MAIN.CAPTCHA_GENERATOR.FONT_OUTLINE_OFFSET_Y * multiplierY,
        1.35
    );
  }

  private BufferedImage resizeIfNeeded(BufferedImage image, int width, int height) {
    if (image.getWidth() != width || image.getHeight() != height) {
      BufferedImage resizedImage = new BufferedImage(width, height, image.getType());

      Graphics2D graphics = resizedImage.createGraphics();
      graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
      graphics.dispose();

      return resizedImage;
    } else {
      return image;
    }
  }

  private void rotate(MapData mapData) {
    byte[] mapImage = mapData.getData();
    byte[] temp = new byte[MapData.MAP_SIZE];
    for (int y = 0; y < MapData.MAP_DIM_SIZE; y++) {
      for (int x = 0; x < MapData.MAP_DIM_SIZE; x++) {
        temp[y * MapData.MAP_DIM_SIZE + x] = mapImage[x * MapData.MAP_DIM_SIZE + MapData.MAP_DIM_SIZE - y - 1];
      }
    }
    System.arraycopy(temp, 0, mapImage, 0, MapData.MAP_SIZE);
  }

  @SuppressWarnings("StatementWithEmptyBody")
  public void generateImages() {
    if (this.shouldStop) {
      return;
    }
    this.shouldStop = true;

    if (this.tempCachedCaptcha != null) {
      this.tempCachedCaptcha.dispose();
    }

    int threadsCount = Runtime.getRuntime().availableProcessors();
    this.tempCachedCaptcha = new CachedCaptcha(this.plugin, threadsCount);
    this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadsCount);
    ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
    LinkedList<Thread> threads = new LinkedList<>();
    this.executor.setThreadFactory(runnable -> {
      Thread thread = new Thread(threadGroup, runnable, "CaptchaGeneratorThread");
      threads.add(thread);
      thread.setPriority(Thread.MIN_PRIORITY);
      return thread;
    });

    for (int i = 0; i < Settings.IMP.MAIN.CAPTCHA_GENERATOR.IMAGES_COUNT; ++i) {
      this.executor.execute(() -> this.genNewPacket(this.tempCachedCaptcha));
    }

    long start = System.currentTimeMillis();
    this.executor.execute(() -> {
      while (this.executor.getCompletedTaskCount() != Settings.IMP.MAIN.CAPTCHA_GENERATOR.IMAGES_COUNT) {
        // Busy wait.
      }

      LimboFilter.getLogger().info("Captcha generated in " + (System.currentTimeMillis() - start) + " ms.");

      if (this.cachedCaptcha != null) {
        this.cachedCaptcha.dispose();
      }

      threads.forEach(this.plugin.getLimboFactory()::releasePreparedPacketThread);
      threads.clear();

      this.cachedCaptcha = this.tempCachedCaptcha;
      this.tempCachedCaptcha = null;
      this.cachedCaptcha.build();
      this.executor.shutdown();
      this.shouldStop = false;
    });
  }

  public void genNewPacket(CachedCaptcha cachedCaptcha) {
    Pair<String, String> answer = this.randomAnswer();

    CraftMapCanvas map = this.nextBackplate();

    RenderedFont renderedFont = this.nextFont();
    map.drawImageCraft(this.painter.drawCaptcha(renderedFont, this.nextColor(), answer.key()),
        this.painter.getWidth(), this.painter.getHeight());
    map.drawImage(this.painter.drawCurves(this.nextCurvesAmount(), this.nextCurveSize()), this.painter.getWidth(), this.painter.getHeight());
    this.exportDatasetImage(map, answer.value());

    Function<MapPalette.MapVersion, MinecraftPacket[]> packet
        = mapVersion -> {
          ThreadLocalRandom random = ThreadLocalRandom.current();
          MinecraftPacket[] packets = new MinecraftPacket[map.getWidth() * map.getHeight()];

          for (int mapId = 0; mapId < packets.length; mapId++) {
            MapData mapData = map.getMapData(mapId, mapVersion);
            if (Settings.IMP.MAIN.FRAMED_CAPTCHA.FRAMED_CAPTCHA_ENABLED
                && random.nextDouble() <= Settings.IMP.MAIN.FRAMED_CAPTCHA.FRAME_ROTATION_CHANCE) {
              for (int j = 0; j < random.nextInt(4); ++j) {
                this.rotate(mapData);
              }
            }
            packets[mapId] = (MinecraftPacket) this.plugin.getPacketFactory().createMapDataPacket(mapId, (byte) 0, mapData);
          }

          return packets;
        };
    MinecraftPacket[] packets17;
    if (this.plugin.getLimboFactory().getPrepareMinVersion().compareTo(ProtocolVersion.MINECRAFT_1_7_6) <= 0) {
      int mapCount = map.getWidth() * map.getHeight();
      packets17 = new MinecraftPacket[MapData.MAP_DIM_SIZE * mapCount];
      for (int mapId = 0; mapId < mapCount; mapId++) {
        MapData[] maps17Data = map.getMaps17Data(mapId);
        for (int i = 0; i < MapData.MAP_DIM_SIZE; ++i) {
          packets17[mapId * MapData.MAP_DIM_SIZE + i] =
              (MinecraftPacket) this.plugin.getPacketFactory().createMapDataPacket(mapId, (byte) 0, maps17Data[i]);
        }
      }
    } else {
      packets17 = new MinecraftPacket[0];
    }

    cachedCaptcha.addCaptchaPacket(answer.value(), packets17, packet);
  }

  private CraftMapCanvas nextBackplate() {
    if (!this.backplateFiles.isEmpty()) {
      Path backplatePath = this.backplateFiles.get(ThreadLocalRandom.current().nextInt(this.backplateFiles.size()));
      CraftMapCanvas cachedBackplate = this.backplateCache.get(backplatePath);
      if (cachedBackplate == null) {
        cachedBackplate = this.loadBackplate(backplatePath);
        this.backplateCache.put(backplatePath, cachedBackplate);
      }

      return new CraftMapCanvas(cachedBackplate);
    }

    if (!this.preparedBackplates.isEmpty()) {
      if (!this.preparedBackplatesIterator.get().hasNext()) {
        this.preparedBackplatesIterator.set(this.preparedBackplates.listIterator());
      }

      return new CraftMapCanvas(this.preparedBackplatesIterator.get().next());
    }

    return this.createCraftMapCanvas();
  }

  public void shutdown() {
    this.shouldStop = true;
    if (this.executor != null) {
      this.executor.shutdownNow();
    }

    if (this.tempCachedCaptcha != null) {
      this.tempCachedCaptcha.dispose();
    }

    if (this.cachedCaptcha != null) {
      this.cachedCaptcha.dispose();
    }

    this.closeDatasetWriters();
  }

  public CaptchaHolder getNextCaptcha() {
    if (this.cachedCaptcha == null) {
      return null;
    } else {
      return this.cachedCaptcha.getNextCaptcha();
    }
  }

  private String spellNumber(int number) {
    StringBuilder result = new StringBuilder();

    Map<String, String> exceptions = Settings.IMP.MAIN.CAPTCHA_GENERATOR.NUMBER_SPELLING_EXCEPTIONS;
    List<List<String>> words = Settings.IMP.MAIN.CAPTCHA_GENERATOR.NUMBER_SPELLING_WORDS;

    int idx = Settings.IMP.MAIN.CAPTCHA_GENERATOR.LENGTH;
    String n = String.valueOf(number);

    while (!n.isEmpty()) {
      if (exceptions.containsKey(n)) {
        result.append(exceptions.get(n)).append(' ');
        break;
      }

      idx--;

      int digit = n.charAt(0) - '0';
      String word = words.get(idx).get(digit);

      if (word != null && !word.isBlank()) {
        result.append(word).append(' ');
      }

      n = n.substring(1);
    }

    return result.toString();
  }

  private Pair<String, String> randomAnswer() {
    int length = this.nextCaptchaLength();
    if (!Settings.IMP.MAIN.CAPTCHA_GENERATOR.NUMBER_SPELLING) {
      String pattern = Settings.IMP.MAIN.CAPTCHA_GENERATOR.PATTERN;

      char[] text = new char[length];
      for (int i = 0; i < length; ++i) {
        text[i] = pattern.charAt(ThreadLocalRandom.current().nextInt(pattern.length()));
      }

      String answer = new String(text);
      return Pair.of(answer, answer);
    } else {
      int min = (int) Math.pow(10, length - 1);
      final int value = ThreadLocalRandom.current().nextInt(min, min * 10);
      return Pair.of(this.spellNumber(value), String.valueOf(value));
    }
  }

  private int nextCaptchaLength() {
    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.RANDOM_LENGTH) {
      int minLength = Math.min(Settings.IMP.MAIN.CAPTCHA_GENERATOR.MIN_LENGTH, Settings.IMP.MAIN.CAPTCHA_GENERATOR.MAX_LENGTH);
      int maxLength = Math.max(Settings.IMP.MAIN.CAPTCHA_GENERATOR.MIN_LENGTH, Settings.IMP.MAIN.CAPTCHA_GENERATOR.MAX_LENGTH);
      return ThreadLocalRandom.current().nextInt(minLength, maxLength + 1);
    }

    return Settings.IMP.MAIN.CAPTCHA_GENERATOR.LENGTH;
  }

  private RenderedFont nextFont() {
    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.RANDOM_VISUAL_SETTINGS) {
      return this.fonts.get(ThreadLocalRandom.current().nextInt(this.fonts.size()));
    }

    if (!this.fontIterator.get().hasNext()) {
      this.fontIterator.set(this.fonts.listIterator());
    }

    return this.fontIterator.get().next();
  }

  private byte[] nextColor() {
    if (Settings.IMP.MAIN.CAPTCHA_GENERATOR.RANDOM_VISUAL_SETTINGS) {
      return this.colors.get(ThreadLocalRandom.current().nextInt(this.colors.size()));
    }

    if (!this.colorIterator.get().hasNext()) {
      this.colorIterator.set(this.colors.listIterator());
    }

    return this.colorIterator.get().next();
  }

  private int nextCurvesAmount() {
    if (!Settings.IMP.MAIN.CAPTCHA_GENERATOR.RANDOM_VISUAL_SETTINGS) {
      return Settings.IMP.MAIN.CAPTCHA_GENERATOR.CURVES_AMOUNT;
    }

    int max = Math.max(1, Settings.IMP.MAIN.CAPTCHA_GENERATOR.CURVES_AMOUNT);
    return ThreadLocalRandom.current().nextInt(1, max + 1);
  }

  private int nextCurveSize() {
    if (!Settings.IMP.MAIN.CAPTCHA_GENERATOR.RANDOM_VISUAL_SETTINGS) {
      return Settings.IMP.MAIN.CAPTCHA_GENERATOR.CURVE_SIZE;
    }

    int max = Math.max(1, Settings.IMP.MAIN.CAPTCHA_GENERATOR.CURVE_SIZE);
    return ThreadLocalRandom.current().nextInt(1, max + 1);
  }

  private void exportDatasetImage(CraftMapCanvas map, String answer) {
    if (!Settings.IMP.MAIN.CAPTCHA_GENERATOR.SAVE_CAPTCHA_DATASET || this.trainLabels == null || this.validationLabels == null) {
      return;
    }

    BufferedImage image = this.toDatasetImage(map);
    int id = this.datasetSequence.incrementAndGet();
    String fileName = String.format("captcha_%08d.png", id);
    boolean useTrain = this.shouldUseTrainSplit();
    Path outputPath = useTrain ? this.datasetTrainPath.resolve(fileName) : this.datasetValidationPath.resolve(fileName);

    try {
      ImageIO.write(image, "png", outputPath.toFile());
      synchronized (this.datasetLock) {
        Writer writer = useTrain ? this.trainLabels : this.validationLabels;
        writer.write(fileName + "," + answer + System.lineSeparator());
        writer.flush();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private boolean shouldUseTrainSplit() {
    int maxTrainImages = Settings.IMP.MAIN.CAPTCHA_GENERATOR.CAPTCHA_DATASET_TRAIN_IMAGES;
    if (this.datasetTrainCounter.get() >= maxTrainImages) {
      this.datasetValidationCounter.incrementAndGet();
      return false;
    }

    double validationSplit = Settings.IMP.MAIN.CAPTCHA_GENERATOR.CAPTCHA_DATASET_VALIDATION_SPLIT;
    boolean validation = ThreadLocalRandom.current().nextDouble() < validationSplit;
    if (validation) {
      this.datasetValidationCounter.incrementAndGet();
      return false;
    }

    this.datasetTrainCounter.incrementAndGet();
    return true;
  }

  private BufferedImage toDatasetImage(CraftMapCanvas map) {
    int width = this.painter.getWidth();
    int height = this.painter.getHeight();
    int mapWidth = map.getWidth();
    byte[][] canvas = map.getCanvas();
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

    for (int canvasY = 0; canvasY < map.getHeight(); canvasY++) {
      for (int canvasX = 0; canvasX < map.getWidth(); canvasX++) {
        int canvasIndex = canvas.length - 1 - canvasY * mapWidth - canvasX;
        byte[] tile = canvas[canvasIndex];
        for (int y = 0; y < MapData.MAP_DIM_SIZE; y++) {
          int imageY = canvasY * MapData.MAP_DIM_SIZE + y;
          for (int x = 0; x < MapData.MAP_DIM_SIZE; x++) {
            int imageX = canvasX * MapData.MAP_DIM_SIZE + x;
            int color = Byte.toUnsignedInt(tile[y * MapData.MAP_DIM_SIZE + x]);
            int rgb = (color << 16) | (color << 8) | color;
            image.setRGB(imageX, imageY, rgb);
          }
        }
      }
    }

    return image;
  }

  private void closeDatasetWriters() {
    try {
      if (this.trainLabels != null) {
        this.trainLabels.close();
      }
      if (this.validationLabels != null) {
        this.validationLabels.close();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    } finally {
      this.trainLabels = null;
      this.validationLabels = null;
      this.datasetTrainPath = null;
      this.datasetValidationPath = null;
    }
  }
}
