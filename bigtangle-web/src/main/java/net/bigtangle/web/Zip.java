package net.bigtangle.web;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 该类实现文件夹压缩成zip文件和zip文件解压
 * 
 * @author Administrator
 *
 */
public class Zip {
	protected static final Logger log = LoggerFactory.getLogger(Zip.class);

	/**
	 * 递归解压zip文件
	 * 
	 * @param zipPath    zip文件路径
	 * @param targetPath 解压后存放的文件路径
	 * @return void
	 */
	public static void unZipRecursion(String zipPath, String targetPath) {
		long start = System.currentTimeMillis();
		// 第一次解压
		boolean flag = unZip(new File(zipPath), targetPath);
		if (flag) {
			// 后续递归解压
			scanFilesWithRecursion(targetPath);
		} else {
			log.info("解压失败");
		}
		long end = System.currentTimeMillis();
		log.info("解压完成， 耗时：{} ms", (end - start));
	}

	/**
	 * 解压zip文件
	 * 
	 * @param srcFile     zip文件路径
	 * @param destDirPath 解压后存放的文件路径
	 * @return boolean
	 */
	public static boolean unZip(File srcFile, String destDirPath) {
		// 判断源文件是否存在
		if (!srcFile.exists()) {
			log.error("此文件不存在：{}", srcFile.getPath());
			return false;
		}
		// 开始解压
		try (ZipFile zipFile = new ZipFile(srcFile, Charset.forName("GBK"))) {
			Enumeration<?> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = (ZipEntry) entries.nextElement();
				// 如果是文件夹，就创建个文件夹
				if (entry.isDirectory()) {
					String dirPath = destDirPath + File.separator + entry.getName();
				} else {
					// 如果是文件，就先创建一个文件，然后用io流把内容copy过去
					File targetFile = new File(destDirPath + File.separator + entry.getName());
					// 保证这个文件的父文件夹必须要存在
					if (!targetFile.getParentFile().exists()) {
						boolean mkdirs = targetFile.getParentFile().mkdirs();
						log.info("保证这个文件的父文件夹必须要存在：{}", mkdirs);
					}
					boolean newFile = targetFile.createNewFile();
					if (newFile) {
						// 将压缩文件内容写入到这个文件中
						// try-with-resources 自动关闭流
						try (InputStream is = zipFile.getInputStream(entry);
								FileOutputStream fos = new FileOutputStream(targetFile)) {
							int len;
							byte[] buf = new byte[2048];
							while ((len = is.read(buf)) != -1) {
								fos.write(buf, 0, len);
							}
						} catch (Exception e) {
							log.error("解压失败", e);
						}
					}
				}
			}
			return true;
		} catch (Exception e) {
			log.error("解压失败", e);
			return false;
		}
	}

	/**
	 * 遍历文件夹，有压缩文件就进行解压
	 * 
	 * @param folderPath 需要解压的文件夹路径
	 * @return void
	 */
	public static void scanFilesWithRecursion(String folderPath) {
		File directory = new File(folderPath);
		if (!directory.isDirectory()) {
			log.error("不是一个文件夹:{}", folderPath);
		}
		// 遍历文件夹
		if (directory.isDirectory()) {
			File[] filelist = directory.listFiles();
			for (int i = 0; i < Objects.requireNonNull(filelist).length; i++) {
				String name = filelist[i].getAbsolutePath()
						.substring(filelist[i].getAbsolutePath().lastIndexOf(".") + 1);
				// 如果是zip文件，解密
				if ("zip".equals(name)) {
					// sum--;
					// 压缩文件名称
					String zipFolderName = filelist[i].getName().substring(0, filelist[i].getName().lastIndexOf("."));
					// 创建解压后的存放文件目录，文件名称为压缩包名称
					String nowUnZipPath = directory.getPath() + File.separator + zipFolderName;
					File nowUnZipPathFile = new File(nowUnZipPath);
					nowUnZipPathFile.mkdirs();
					boolean flag = unZip(new File(filelist[i].getAbsolutePath()), nowUnZipPath);
					if (flag) {
						// 解压成功，删除压缩包
						boolean deleteFlag = filelist[i].delete();
						log.info("解压成功，删除临时压缩包,路径：{},是否删除成功：{}", filelist[i].getPath(), deleteFlag);
					}
					// 递归
					scanFilesWithRecursion(nowUnZipPathFile.getPath());
				} else if (new File(filelist[i].getPath()).isDirectory()) {
					// 递归
					scanFilesWithRecursion(filelist[i].getPath());
				}
			}
		}
	}

}
