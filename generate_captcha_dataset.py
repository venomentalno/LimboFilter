#!/usr/bin/env python3
"""
LimboFilter Dataset Generator

Generates a dataset of CAPTCHA images with random text (3-6 letters).
Uses backplates and fonts from specified directories with randomized effects.
Outputs images and a CSV file with filename;label format.

Designed to be compatible with GitHub Actions.
"""

import os
import sys
import random
import string
import argparse
from pathlib import Path
from typing import List, Tuple, Optional

try:
    from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance, ImageChops
    import numpy as np
except ImportError as e:
    print(f"Error: Missing required dependency: {e}")
    print("Please install required packages: pip install Pillow numpy")
    sys.exit(1)


class CaptchaGenerator:
    """Generates CAPTCHA images with various randomization options."""
    
    def __init__(
        self,
        backplate_dir: Optional[str] = None,
        font_dir: Optional[str] = None,
        output_dir: str = "./dataset",
        width: int = 200,
        height: int = 80,
        min_length: int = 3,
        max_length: int = 6,
        use_default_fonts: bool = True,
    ):
        self.backplate_dir = Path(backplate_dir) if backplate_dir else None
        self.font_dir = Path(font_dir) if font_dir else None
        self.output_dir = Path(output_dir)
        self.width = width
        self.height = height
        self.min_length = min_length
        self.max_length = max_length
        self.use_default_fonts = use_default_fonts
        
        # Load available resources
        self.backplates = self._load_backplates()
        self.fonts = self._load_fonts()
        
        # Effect configuration
        self.effect_configs = {
            'noise_dots': {'enabled': True, 'probability': 0.7},
            'noise_lines': {'enabled': True, 'probability': 0.6},
            'blur': {'enabled': True, 'probability': 0.4},
            'warp': {'enabled': True, 'probability': 0.5},
            'color_jitter': {'enabled': True, 'probability': 0.6},
            'contrast_adjust': {'enabled': True, 'probability': 0.5},
        }
        
    def _load_backplates(self) -> List[Path]:
        """Load backplate images from directory."""
        backplates = []
        if self.backplate_dir and self.backplate_dir.exists():
            valid_extensions = {'.png', '.jpg', '.jpeg', '.bmp', '.gif', '.webp'}
            for ext in valid_extensions:
                backplates.extend(self.backplate_dir.glob(f'*{ext}'))
                backplates.extend(self.backplate_dir.glob(f'*{ext.upper()}'))
            print(f"Loaded {len(backplates)} backplates from {self.backplate_dir}")
        return backplates
    
    def _load_fonts(self) -> List[Path]:
        """Load font files from directory."""
        fonts = []
        if self.font_dir and self.font_dir.exists():
            valid_extensions = {'.ttf', '.otf', '.ttc'}
            for ext in valid_extensions:
                fonts.extend(self.font_dir.glob(f'*{ext}'))
                fonts.extend(self.font_dir.glob(f'*{ext.upper()}'))
            print(f"Loaded {len(fonts)} fonts from {self.font_dir}")
        return fonts
    
    def _get_random_text(self) -> str:
        """Generate random text with length between min_length and max_length."""
        length = random.randint(self.min_length, self.max_length)
        # Use uppercase letters for better readability
        return ''.join(random.choices(string.ascii_uppercase, k=length))
    
    def _get_random_font(self, size: int = 36) -> ImageFont.FreeTypeFont:
        """Get a random font from available fonts or default."""
        font_candidates = []
        
        # Add custom fonts if available
        if self.fonts:
            font_candidates.extend(self.fonts)
        
        # Add default fonts
        if self.use_default_fonts:
            default_fonts = [
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
                "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
                "/usr/share/fonts/truetype/freefont/FreeMono.ttf",
            ]
            for font_path in default_fonts:
                if os.path.exists(font_path):
                    font_candidates.append(Path(font_path))
        
        # Select random font
        if font_candidates:
            font_path = random.choice(font_candidates)
            try:
                return ImageFont.truetype(str(font_path), size)
            except (IOError, OSError):
                pass
        
        # Fallback to default
        return ImageFont.load_default()
    
    def _apply_random_background(self, image: Image.Image) -> Image.Image:
        """Apply random background or backplate."""
        if self.backplates and random.random() < 0.8:  # 80% chance to use backplate
            backplate_path = random.choice(self.backplates)
            try:
                backplate = Image.open(backplate_path).convert('RGB')
                backplate = backplate.resize((self.width, self.height), Image.Resampling.LANCZOS)
                
                # Random blend mode
                blend_mode = random.choice(['normal', 'overlay', 'multiply', 'screen'])
                if blend_mode == 'normal':
                    return backplate
                elif blend_mode == 'overlay':
                    return Image.blend(image, backplate, alpha=0.3)
                elif blend_mode == 'multiply':
                    return ImageChops.multiply(image, backplate)
                elif blend_mode == 'screen':
                    return ImageChops.screen(image, backplate)
            except Exception as e:
                pass
        
        # Generate random gradient background
        return self._generate_gradient_background()
    
    def _generate_gradient_background(self) -> Image.Image:
        """Generate a random gradient background."""
        image = Image.new('RGB', (self.width, self.height))
        draw = ImageDraw.Draw(image)
        
        # Random colors
        color1 = tuple(random.randint(50, 200) for _ in range(3))
        color2 = tuple(random.randint(50, 200) for _ in range(3))
        
        # Random gradient direction
        direction = random.choice(['horizontal', 'vertical', 'diagonal'])
        
        if direction == 'horizontal':
            for x in range(self.width):
                ratio = x / self.width
                color = tuple(int(color1[i] + (color2[i] - color1[i]) * ratio) for i in range(3))
                draw.line((x, 0, x, self.height), fill=color)
        elif direction == 'vertical':
            for y in range(self.height):
                ratio = y / self.height
                color = tuple(int(color1[i] + (color2[i] - color1[i]) * ratio) for i in range(3))
                draw.line((0, y, self.width, y), fill=color)
        else:  # diagonal
            for i in range(self.width + self.height):
                ratio = i / (self.width + self.height)
                color = tuple(int(color1[i] + (color2[i] - color1[i]) * ratio) for i in range(3))
                x = max(0, min(i, self.width - 1))
                y = max(0, min(i - x, self.height - 1))
                draw.line((x, y, min(x + 20, self.width), min(y + 20, self.height)), fill=color)
        
        return image
    
    def _add_noise_dots(self, image: Image.Image, count: int = 100) -> Image.Image:
        """Add random noise dots to the image."""
        draw = ImageDraw.Draw(image)
        for _ in range(count):
            x = random.randint(0, self.width - 1)
            y = random.randint(0, self.height - 1)
            color = tuple(random.randint(0, 255) for _ in range(3))
            size = random.randint(1, 3)
            draw.ellipse([x, y, x + size, y + size], fill=color)
        return image
    
    def _add_noise_lines(self, image: Image.Image, count: int = 5) -> Image.Image:
        """Add random noise lines to the image."""
        draw = ImageDraw.Draw(image)
        for _ in range(count):
            start = (random.randint(0, self.width), random.randint(0, self.height))
            end = (random.randint(0, self.width), random.randint(0, self.height))
            color = tuple(random.randint(0, 255) for _ in range(3))
            width = random.randint(1, 3)
            draw.line([start, end], fill=color, width=width)
        return image
    
    def _apply_warp(self, image: Image.Image) -> Image.Image:
        """Apply random warp distortion to the image."""
        try:
            # Create displacement map
            dx = np.random.uniform(-5, 5, (self.height, self.width)).astype(np.float32)
            dy = np.random.uniform(-5, 5, (self.height, self.width)).astype(np.float32)
            
            # Apply smooth displacement
            from scipy.ndimage import gaussian_filter
            dx = gaussian_filter(dx, sigma=5)
            dy = gaussian_filter(dy, sigma=5)
            
            # Create mesh grid
            y, x = np.mgrid[:self.height, :self.width]
            indices = (y + dy, x + dx)
            
            # Apply transformation to each channel
            img_array = np.array(image)
            warped = np.zeros_like(img_array)
            for c in range(img_array.shape[2]):
                from scipy.ndimage import map_coordinates
                warped[:, :, c] = map_coordinates(img_array[:, :, c], indices, order=1, mode='reflect')
            
            return Image.fromarray(warped.astype(np.uint8))
        except ImportError:
            # Fallback to simple affine transform
            angle = random.uniform(-15, 15)
            return image.rotate(angle, resample=Image.Resampling.BICUBIC, expand=False)
    
    def _apply_effects(self, image: Image.Image) -> Image.Image:
        """Apply random effects to the image."""
        # Noise dots
        if random.random() < self.effect_configs['noise_dots']['probability']:
            dot_count = random.randint(50, 200)
            image = self._add_noise_dots(image, dot_count)
        
        # Noise lines
        if random.random() < self.effect_configs['noise_lines']['probability']:
            line_count = random.randint(3, 8)
            image = self._add_noise_lines(image, line_count)
        
        # Blur
        if random.random() < self.effect_configs['blur']['probability']:
            blur_radius = random.uniform(0.5, 2.0)
            image = image.filter(ImageFilter.GaussianBlur(radius=blur_radius))
        
        # Warp
        if random.random() < self.effect_configs['warp']['probability']:
            image = self._apply_warp(image)
        
        # Color jitter
        if random.random() < self.effect_configs['color_jitter']['probability']:
            enhancer = ImageEnhance.Color(image)
            factor = random.uniform(0.5, 1.5)
            image = enhancer.enhance(factor)
        
        # Contrast adjustment
        if random.random() < self.effect_configs['contrast_adjust']['probability']:
            enhancer = ImageEnhance.Contrast(image)
            factor = random.uniform(0.8, 1.3)
            image = enhancer.enhance(factor)
        
        return image
    
    def generate_captcha(self, text: Optional[str] = None) -> Tuple[Image.Image, str]:
        """Generate a single CAPTCHA image."""
        if text is None:
            text = self._get_random_text()
        
        # Create base image
        image = Image.new('RGB', (self.width, self.height), color=(240, 240, 240))
        
        # Apply background
        image = self._apply_random_background(image)
        
        # Draw text
        draw = ImageDraw.Draw(image)
        
        # Random font settings
        font_size = random.randint(28, 48)
        font = self._get_random_font(font_size)
        
        # Random text color
        text_color = tuple(random.randint(0, 150) for _ in range(3))
        
        # Calculate text position (centered with some randomness)
        bbox = draw.textbbox((0, 0), text, font=font)
        text_width = bbox[2] - bbox[0]
        text_height = bbox[3] - bbox[1]
        
        base_x = (self.width - text_width) // 2
        base_y = (self.height - text_height) // 2
        
        # Add randomness to position
        offset_x = random.randint(-10, 10)
        offset_y = random.randint(-5, 5)
        
        position = (base_x + offset_x, base_y + offset_y)
        
        # Draw text with random rotation per character (optional)
        if random.random() < 0.3:  # 30% chance for individual character rotation
            current_x = position[0]
            for char in text:
                char_image = Image.new('RGBA', (font_size + 10, font_size + 10), (0, 0, 0, 0))
                char_draw = ImageDraw.Draw(char_image)
                char_draw.text((5, 0), char, font=font, fill=text_color)
                
                # Random rotation
                angle = random.uniform(-20, 20)
                char_image = char_image.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
                
                image.paste(char_image, (current_x, position[1]), char_image)
                current_x += font_size + random.randint(-5, 5)
        else:
            # Draw all text at once
            draw.text(position, text, font=font, fill=text_color)
        
        # Apply effects
        image = self._apply_effects(image)
        
        return image, text
    
    def generate_dataset(
        self,
        num_images: int = 10000,
        csv_filename: str = "captchas.csv",
        batch_size: int = 100,
    ) -> str:
        """Generate a dataset of CAPTCHA images."""
        # Create output directory
        self.output_dir.mkdir(parents=True, exist_ok=True)
        images_dir = self.output_dir / "images"
        images_dir.mkdir(exist_ok=True)
        
        csv_path = self.output_dir / csv_filename
        labels = []
        
        print(f"Generating {num_images} CAPTCHA images...")
        print(f"Output directory: {self.output_dir.absolute()}")
        
        for i in range(num_images):
            # Generate CAPTCHA
            image, text = self.generate_captcha()
            
            # Save image
            filename = f"{i:06d}.png"
            image_path = images_dir / filename
            image.save(image_path, "PNG")
            
            # Store label
            labels.append(f"{filename};{text}")
            
            # Progress reporting
            if (i + 1) % batch_size == 0 or i == num_images - 1:
                progress = (i + 1) / num_images * 100
                print(f"Progress: {i + 1}/{num_images} ({progress:.1f}%)")
        
        # Write CSV
        with open(csv_path, 'w', encoding='utf-8') as f:
            f.write("file_name;label\n")
            for label in labels:
                f.write(label + "\n")
        
        print(f"\nDataset generation complete!")
        print(f"Images saved to: {images_dir.absolute()}")
        print(f"CSV file saved to: {csv_path.absolute()}")
        print(f"Total images: {num_images}")
        
        return str(csv_path)


def main():
    parser = argparse.ArgumentParser(
        description="Generate CAPTCHA dataset with random text and effects."
    )
    
    # Directory arguments
    parser.add_argument(
        "--backplates", "-b",
        type=str,
        default=None,
        help="Path to backplates directory (default: None)"
    )
    parser.add_argument(
        "--fonts", "-f",
        type=str,
        default=None,
        help="Path to fonts directory (default: None)"
    )
    parser.add_argument(
        "--output", "-o",
        type=str,
        default="./dataset",
        help="Output directory for dataset (default: ./dataset)"
    )
    
    # Generation parameters
    parser.add_argument(
        "--count", "-n",
        type=int,
        default=10000,
        help="Number of images to generate (default: 10000)"
    )
    parser.add_argument(
        "--min-length",
        type=int,
        default=3,
        help="Minimum text length (default: 3)"
    )
    parser.add_argument(
        "--max-length",
        type=int,
        default=6,
        help="Maximum text length (default: 6)"
    )
    parser.add_argument(
        "--width",
        type=int,
        default=200,
        help="Image width in pixels (default: 200)"
    )
    parser.add_argument(
        "--height",
        type=int,
        default=80,
        help="Image height in pixels (default: 80)"
    )
    parser.add_argument(
        "--no-default-fonts",
        action="store_true",
        help="Disable default system fonts"
    )
    parser.add_argument(
        "--csv-name",
        type=str,
        default="captchas.csv",
        help="Name of the CSV file (default: captchas.csv)"
    )
    
    args = parser.parse_args()
    
    # Convert Windows paths if running on Linux (for GitHub Actions compatibility)
    backplate_dir = args.backplates
    font_dir = args.fonts
    output_dir = args.output
    
    # Handle Windows-style paths
    if backplate_dir and backplate_dir.startswith('C:\\'):
        backplate_dir = backplate_dir.replace('C:\\Users\\V\\PycharmProjects\\aicrypto\\backplates', '/workspace/backplates')
    if font_dir and font_dir.startswith('C:\\'):
        font_dir = font_dir.replace('C:\\Users\\V\\PycharmProjects\\aicrypto\\fonts', '/workspace/fonts')
    if output_dir and output_dir.startswith('S:\\'):
        output_dir = output_dir.replace('S:\\dataset', '/workspace/dataset')
    
    # Create generator
    generator = CaptchaGenerator(
        backplate_dir=backplate_dir,
        font_dir=font_dir,
        output_dir=output_dir,
        width=args.width,
        height=args.height,
        min_length=args.min_length,
        max_length=args.max_length,
        use_default_fonts=not args.no_default_fonts,
    )
    
    # Generate dataset
    generator.generate_dataset(
        num_images=args.count,
        csv_filename=args.csv_name,
    )


if __name__ == "__main__":
    main()
