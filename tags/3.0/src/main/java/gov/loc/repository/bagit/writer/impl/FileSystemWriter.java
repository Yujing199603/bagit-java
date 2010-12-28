package gov.loc.repository.bagit.writer.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import gov.loc.repository.bagit.Bag;
import gov.loc.repository.bagit.BagFactory;
import gov.loc.repository.bagit.BagFile;
import gov.loc.repository.bagit.Bag.Format;
import gov.loc.repository.bagit.BagFactory.LoadOption;
import gov.loc.repository.bagit.impl.VFSBagFile;
import gov.loc.repository.bagit.utilities.FilenameHelper;
import gov.loc.repository.bagit.utilities.VFSHelper;

public class FileSystemWriter extends AbstractWriter {

	private static final Log log = LogFactory.getLog(FileSystemWriter.class);
	
	private static final int BUFFERSIZE = 65536;
	
	private File newBagDir;
	private boolean skipIfPayloadFileExists = true;
	private Bag newBag;
	private String newBagURI;
	private int fileTotal = 0;
	private int fileCount = 0;
	
	public FileSystemWriter(BagFactory bagFactory) {
		super(bagFactory);
	}
	
	public void setSkipIfPayloadFileExists(boolean skip) {
		this.skipIfPayloadFileExists = skip;
	}

	@Override
	protected Format getFormat(File file) {
		return Format.FILESYSTEM;
	}
	
	@Override
	public void startBag(Bag bag) {
		try {
			if (newBagDir.exists()) {
				if (! newBagDir.isDirectory()) {
					throw new RuntimeException(MessageFormat.format("Bag directory {0} is not a directory.", newBagDir.toString()));
				}
			} else {
				FileUtils.forceMkdir(newBagDir);
			}
		} catch(Exception ex) {
			throw new RuntimeException(ex);
		}
		this.newBag = this.bagFactory.createBag(this.newBagDir, bag.getBagConstants().getVersion(), LoadOption.NO_LOAD);
		this.newBagURI = VFSHelper.getUri(this.newBagDir, Format.FILESYSTEM);
		this.fileCount = 0;
		this.fileTotal = bag.getTags().size() + bag.getPayload().size();
	}
	
	@Override
	public void visitPayload(BagFile bagFile) {
		this.fileCount++;
		this.progress("writing", bagFile.getFilepath(), this.fileCount, this.fileTotal);
		File file = new File(this.newBagDir, bagFile.getFilepath());
		if (! this.skipIfPayloadFileExists || ! file.exists()) {
			log.debug(MessageFormat.format("Writing payload file {0} to {1}.", bagFile.getFilepath(), file.toString()));
			this.write(bagFile, file);	
		} else {
			log.debug(MessageFormat.format("Skipping writing payload file {0} to {1}.", bagFile.getFilepath(), file.toString()));
		}
		this.newBag.putBagFile(new VFSBagFile(bagFile.getFilepath(), VFSHelper.concatUri(this.newBagURI, bagFile.getFilepath())));
	}
	
	@Override
	public void visitTag(BagFile bagFile) {
		this.fileCount++;
		this.progress("writing", bagFile.getFilepath(), this.fileCount, this.fileTotal);
		File file = new File(this.newBagDir, bagFile.getFilepath());
		log.debug(MessageFormat.format("Writing tag file {0} to {1}.", bagFile.getFilepath(), file.toString()));		
		this.write(bagFile, file);
		this.newBag.putBagFile(new VFSBagFile(bagFile.getFilepath(), VFSHelper.concatUri(this.newBagURI, bagFile.getFilepath())));
	}
	
	private void write(BagFile bagFile, File file) {
		try {
			File parentDir = file.getParentFile();
			if (! parentDir.exists()) {
				FileUtils.forceMkdir(parentDir);
			}
			
			FileOutputStream out = new FileOutputStream(this.getTempFile(file));
			InputStream in = bagFile.newInputStream();
			byte[] dataBytes = new byte[BUFFERSIZE];
			int nread = in.read(dataBytes);
			while (nread > 0) {
				out.write(dataBytes, 0, nread);
			    nread = in.read(dataBytes);
			}
			in.close();
			out.close();
			this.switchTemp(file);
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
		
	}

	@Override
	public Bag write(Bag bag, File file) {
		log.info("Writing bag");
		this.newBagDir = file;
		bag.accept(this);
		if (this.newBagDir.equals(bag.getFile())) {
			log.debug("Removing any extra files or directories");
			this.removeExtraFiles(this.newBagDir);
		}
		if (this.isCancelled()) return null;
		return this.newBag;		

	}
	
	private void removeExtraFiles(File dir) {
		log.trace(MessageFormat.format("Checking children of {0} for removal", dir));
		for(File file : dir.listFiles()) {
			if (this.isCancelled()) return;
			if (file.isDirectory()) {
				if (log.isTraceEnabled()) {
					log.trace(MessageFormat.format("{0} is a directory with {1} children", file, file.listFiles().length));
				}
				this.removeExtraFiles(file);
				if (log.isTraceEnabled()) {
					log.trace(MessageFormat.format("{0} now has {1} children", file, file.listFiles().length));
				}				
				if (file.listFiles().length == 0) {					
					try {
						log.trace("Deleting " + file);
						FileUtils.forceDelete(file);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			} else {
				String filepath = FilenameHelper.removeBasePath(this.newBagDir.toString(), file.toString());
				log.trace(MessageFormat.format("{0} is a file whose filepath is {1}", file, filepath));
				if (this.newBag.getBagFile(filepath) == null) {
					try {
						log.trace("Deleting " + file);
						FileUtils.forceDelete(file);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}					
				}				
			}
		}
		
	}
	
}