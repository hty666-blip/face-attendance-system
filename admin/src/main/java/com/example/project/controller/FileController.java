package com.example.project.controller;
import cn.hutool.core.lang.Dict;
import com.example.project.common.Result;
import com.example.project.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
/** 当前上传用途为共享头像与公告图片，重编码保存；所有图片下载仍须登录。 */
@RestController @RequestMapping("/file")
public class FileController {
    @Value("${file.storage-path:./files}") private String storage;
    @Value("${file.public-base-url:http://localhost:9090}") private String publicBase;
    @PostMapping("/upload") public Result upload(@RequestParam MultipartFile file) throws IOException { return Result.success(store(file)); }
    @PostMapping("/uploadImg") public Dict uploadImg(@RequestParam MultipartFile file) throws IOException {
        return Dict.create().set("errno",0).set("data", Dict.create().set("url",store(file)));
    }
    private String store(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new ServiceException("图片须小于5MB");
        BufferedImage image;
        try (InputStream input = file.getInputStream(); javax.imageio.stream.ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ServiceException("仅支持PNG或JPEG图片");
            javax.imageio.ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw new ServiceException("仅支持PNG或JPEG图片");
                reader.setInput(stream);
                if ((long)reader.getWidth(0)*reader.getHeight(0) > 16_000_000) throw new ServiceException("图片分辨率过大");
                image = reader.read(0);
            } finally { reader.dispose(); }
        }
        String name = UUID.randomUUID().toString().replace("-", "") + ".png";
        Path directory = Paths.get(storage).toAbsolutePath().normalize(); Files.createDirectories(directory);
        if (!ImageIO.write(image, "png", directory.resolve(name).toFile())) throw new IOException("图片保存失败");
        return publicBase.replaceAll("/$", "") + "/file/download/" + name;
    }
    @GetMapping("/download/{name}") public ResponseEntity<byte[]> download(@PathVariable String name) throws IOException {
        if (!name.matches("[a-f0-9]{32}\\.(png|jpg|jpeg)")) throw new ServiceException("404", "图片不存在");
        Path directory = Paths.get(storage).toAbsolutePath().normalize(); Path path = directory.resolve(name).normalize();
        if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(directory.toRealPath())) throw new ServiceException("404", "图片不存在");
        if (Files.size(path) > 5 * 1024 * 1024) throw new ServiceException("图片过大");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(name.endsWith("png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG).body(Files.readAllBytes(path));
    }
}
