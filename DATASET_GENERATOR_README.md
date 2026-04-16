# LimboFilter CAPTCHA Dataset Generator

This tool generates a dataset of CAPTCHA images with random text (3-6 letters) for training machine learning models or testing CAPTCHA solvers.

## Features

- **Random Text**: Generates CAPTCHAs with 3-6 character random text (configurable)
- **Custom Backplates**: Uses backplate images from your specified directory (~40k images supported)
- **Multiple Fonts**: Supports custom fonts from your directory plus default system fonts
- **Randomized Effects**: 
  - Noise dots and lines
  - Blur effects
  - Warp/distortion effects
  - Color jitter
  - Contrast adjustments
  - Random character rotation
- **GitHub Actions Compatible**: Includes workflow file for CI/CD generation
- **CSV Labels**: Outputs labels in `file_name;label` format

## Installation

### Local Installation

```bash
# Install Python dependencies
pip install -r requirements.txt

# Optional: Install system fonts for better variety
# Ubuntu/Debian:
sudo apt-get install fonts-dejavu fonts-liberation fonts-freefont-otf

# Windows: Fonts are usually pre-installed
```

### GitHub Actions

The workflow is pre-configured. Simply trigger it from the Actions tab in your GitHub repository.

## Usage

### Command Line

```bash
python generate_captcha_dataset.py [OPTIONS]
```

#### Options

| Option | Short | Default | Description |
|--------|-------|---------|-------------|
| `--backplates` | `-b` | `None` | Path to backplates directory |
| `--fonts` | `-f` | `None` | Path to fonts directory |
| `--output` | `-o` | `./dataset` | Output directory for dataset |
| `--count` | `-n` | `10000` | Number of images to generate |
| `--min-length` | | `3` | Minimum text length |
| `--max-length` | | `6` | Maximum text length |
| `--width` | | `200` | Image width in pixels |
| `--height` | | `80` | Image height in pixels |
| `--no-default-fonts` | | `False` | Disable default system fonts |
| `--csv-name` | | `captchas.csv` | Name of the CSV file |

### Examples

#### Basic usage (default settings):
```bash
python generate_captcha_dataset.py
```

#### With custom backplates and fonts (Windows paths):
```bash
python generate_captcha_dataset.py \
  --backplates "C:\Users\V\PycharmProjects\aicrypto\backplates" \
  --fonts "C:\Users\V\PycharmProjects\aicrypto\fonts" \
  --output "S:\dataset" \
  --count 50000
```

#### With custom backplates and fonts (Linux/GitHub Actions paths):
```bash
python generate_captcha_dataset.py \
  --backplates /path/to/backplates \
  --fonts /path/to/fonts \
  --output /path/to/dataset \
  --count 50000 \
  --min-length 4 \
  --max-length 5
```

#### Generate smaller test dataset:
```bash
python generate_captcha_dataset.py \
  --count 1000 \
  --min-length 3 \
  --max-length 4
```

### GitHub Actions

1. Go to the **Actions** tab in your GitHub repository
2. Select **Generate CAPTCHA Dataset** workflow
3. Click **Run workflow**
4. Optionally customize:
   - Number of images (default: 10000)
   - Min/max text length
5. The dataset will be uploaded as an artifact

#### Adding Your Own Backplates/Fonts

To use your own backplates and fonts in GitHub Actions:

1. Upload them to your repository (if small enough)
2. Or download them during the workflow:

```yaml
- name: Download backplates
  run: |
    wget -O backplates.zip "YOUR_URL_HERE"
    unzip backplates.zip -d ./backplates

- name: Download fonts
  run: |
    wget -O fonts.zip "YOUR_URL_HERE"
    unzip fonts.zip -d ./fonts
```

Then modify the generate step:

```yaml
- name: Generate CAPTCHA dataset
  run: |
    python generate_captcha_dataset.py \
      --backplates ./backplates \
      --fonts ./fonts \
      --output ./dataset \
      --count ${{ github.event.inputs.num_images }}
```

## Output Format

### Directory Structure
```
dataset/
├── images/
│   ├── 000000.png
│   ├── 000001.png
│   ├── 000002.png
│   └── ...
└── captchas.csv
```

### CSV Format
```csv
file_name;label
000000.png;ABC123
000001.png;XYZ789
000002.png;DEF456
...
```

## Customization

### Modifying Effects

Edit the `effect_configs` dictionary in `generate_captcha_dataset.py`:

```python
self.effect_configs = {
    'noise_dots': {'enabled': True, 'probability': 0.7},
    'noise_lines': {'enabled': True, 'probability': 0.6},
    'blur': {'enabled': True, 'probability': 0.4},
    'warp': {'enabled': True, 'probability': 0.5},
    'color_jitter': {'enabled': True, 'probability': 0.6},
    'contrast_adjust': {'enabled': True, 'probability': 0.5},
}
```

Adjust probabilities (0.0 to 1.0) to control how often each effect is applied.

### Adding New Effects

Add new methods to the `CaptchaGenerator` class and call them in `_apply_effects()`.

## Performance Tips

1. **Backplates**: Having ~40k backplates is great for diversity. The script randomly selects from available backplates.

2. **Memory**: For very large datasets (>100k images), consider:
   - Running in batches
   - Using a machine with more RAM
   - Reducing image dimensions

3. **Speed**: Generation speed depends on:
   - Number of effects applied
   - Image dimensions
   - CPU cores available

## License

This tool is part of the LimboFilter project. See the main repository for license information.

## Troubleshooting

### Missing Fonts
If you get font-related errors:
- Install system fonts: `sudo apt-get install fonts-dejavu`
- Or provide your own font directory with `.ttf` files

### Memory Issues
For out-of-memory errors:
- Reduce the number of images per batch
- Decrease image dimensions
- Disable some effects (especially warp which uses numpy arrays)

### Path Issues on Windows
Use forward slashes or escape backslashes:
```bash
# Both work:
--backplates "C:/Users/V/PycharmProjects/aicrypto/backplates"
--backplates "C:\\Users\\V\\PycharmProjects\\aicrypto\\backplates"
```
