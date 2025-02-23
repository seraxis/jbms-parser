package bms.model;

import bms.model.osu.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class OSUDecoder extends ChartDecoder {

	private BMSModel model;

	public OSUDecoder(int lntype) { this.lntype = lntype; }

	public BMSModel decode(ChartInformation info) {
		this.lntype = info.lntype;
		return decode(info.path);
	}

	public BMSModel decode(Path f) {
		MessageDigest md5digest, sha256digest;
		try {
			md5digest = MessageDigest.getInstance("MD5");
			sha256digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e1) {
			e1.printStackTrace();
			return null;
		}
		BufferedReader br;
		try {
			br = new BufferedReader(new InputStreamReader(
					new DigestInputStream(new DigestInputStream(new ByteArrayInputStream(Files.readAllBytes(f)), md5digest), sha256digest),
					"MS932"));

		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		Osu osu = new Osu(br);
		model = new BMSModel();
		model.setMD5(BMSDecoder.convertHexString(md5digest.digest()));
		model.setSHA256(BMSDecoder.convertHexString(sha256digest.digest()));
		if (osu.general.mode != 3) return null;

		int keymode = osu.difficulty.circleSize.intValue();
		model.setTitle(osu.metadata.title);
		model.setSubTitle("[" + osu.metadata.version + "]");
		model.setArtist(osu.metadata.artist);
		model.setSubArtist(osu.metadata.creator);
		model.setGenre(keymode + "K");
		model.setJudgerank(3);
		model.setJudgerankType(BMSModel.JudgeRankType.BMS_RANK);
		model.setTotal(0);
		model.setTotalType(BMSModel.TotalType.BMS);
		model.setPlaylevel("");
		int[] mapping;
		switch (keymode) {
			case 4: {
				model.setMode(Mode.BEAT_7K);
				mapping = new int[]{0, 2, 4, 6, -1, -1, -1, -1};
				break;
			}
			case 5: {
				model.setMode(Mode.BEAT_5K);
				mapping = new int[]{0, 1, 2, 3, 4, -1};
				break;
			}
			case 6: {
				model.setMode(Mode.BEAT_7K);
				mapping = new int[]{0, 1, 2, 4, 5, 6, -1};
				break;
			}
			case 7: {
				model.setMode(Mode.BEAT_7K);
				mapping = new int[]{0, 1, 2, 3, 4, 5, 6, -1};
				break;
			}
			case 8: {
				model.setMode(Mode.BEAT_7K);
				mapping = new int[]{7, 0, 1, 2, 3, 4, 5, 6};
				break;
			}
			case 9: {
				model.setMode(Mode.POPN_9K);
				mapping = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8};
				break;
			}
			case 10: {
				model.setMode(Mode.BEAT_10K);
				mapping = new int[]{0, 1, 2, 3, 4, 6, 7, 8, 9, 10, -1, -1};
				break;
			}
			case 12: {
				model.setMode(Mode.BEAT_10K);
				mapping = new int[]{5, 0, 1, 2, 3, 4, 6, 7, 8, 9, 10, 11};
				break;
			}
			case 14: {
				model.setMode(Mode.BEAT_14K);
				mapping = new int[]{0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, -1};
				break;
			}
			case 16: {
				model.setMode(Mode.BEAT_14K);
				mapping = new int[]{7, 0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14};
				break;
			}
			default:
				return null;
		}
		model.setLnmode(BMSModel.LNTYPE_LONGNOTE);
		model.setBanner("");
		for (int i = 0; i < osu.events.size(); i++) {
			try {
				Events event = osu.events.get(i);
				if (Integer.parseInt(event.eventType) != 0) continue;
				model.setBackbmp(event.eventParams.get(0));
				model.setStagefile(event.eventParams.get(0));
			} catch (NumberFormatException e) {
				continue;
			}
		}
		model.setPreview("");

		TreeMap<Integer, TimeLine> timelines = new TreeMap<Integer, TimeLine>();
		TreeMap<Integer, Float> svs = new TreeMap<Integer, Float>();
		TreeMap<Integer, TimingPoints> timingPoints = new TreeMap<Integer, TimingPoints>();
		for (int i = 0; i < osu.timingPoints.size(); i++) {
			TimingPoints point = osu.timingPoints.get(i);
			if (point.uninherited) {
				timingPoints.put(point.time.intValue(), point);
			} else {
				float sv = point.beatLength;
				sv = 100.f / (-sv);
				svs.put(point.time.intValue(), sv);
			}
		}
		model.setBpm(1 / timingPoints.firstEntry().getValue().beatLength * 1000 * 60);
		Vector<String> wavmap = new Vector<String>();
		wavmap.add(osu.general.audioFilename);
		wavmap.add("");
		TimeLine bgmTl = new TimeLine(0, 0, model.getMode().key);
		Note bgm = new NormalNote(0, 0, 0);
		bgmTl.addBackGroundNote(bgm);
		bgmTl.setBPM(model.getBpm());
		bgmTl.setBGA(0);
		timelines.put(0, bgmTl);

		TimingPoints timingPoint = timingPoints.firstEntry().getValue();
		//TimingPoints nextTimingPoint = timingPoints.higherEntry(timingPoint.time.intValue()) != null ?
		//		timingPoints.higherEntry(timingPoint.time.intValue()).getValue() : null;
		//float sv = svs.firstEntry() != null ? svs.firstEntry().getValue() : 1.f;
		for (int i = 0; i < osu.hitObjects.size(); i++) {
			HitObjects hitObject = osu.hitObjects.get(i);

			int columnIdx = ((int) Math.floor(hitObject.x * keymode / 512.f));
			columnIdx = Math.max(0, Math.min(keymode - 1, columnIdx));
			double section = hitObject.time / (timingPoint.beatLength * timingPoint.meter);

			TimeLine timeline = timelines.get(hitObject.time);
			if (timeline == null) {
				timeline = new TimeLine(section, hitObject.time.longValue() * 1000, model.getMode().key);
				timelines.put(hitObject.time, timeline);
			}
			timeline.setBPM(model.getBpm());
			Boolean isManiaHold = (hitObject.type & 0x80) > 0;
			int wavIdx = 1;
		/*if (!hitObject.hitSample.filename.isEmpty()) { // keysounds potentially go here.
			wavIdx = wavmap.size();
			wavmap.add(hitObject.hitSample.filename);
		}*/
			if (isManiaHold) {
				LongNote head = new LongNote(wavIdx, hitObject.time * 1000, 0);
				timeline.setNote(mapping[columnIdx], head);

				int tailTimeMs = Integer.parseInt(hitObject.objectParams.get(0));
				long tailTimeUs = (long) tailTimeMs * 1000;
				double tailSection = (double) tailTimeMs / (timingPoint.beatLength * timingPoint.meter);
				LongNote tail = new LongNote(wavIdx, tailTimeUs, 0);

				TimeLine tailTl = timelines.get(tailTimeMs);
				if (tailTl == null) {
					tailTl = new TimeLine(tailSection, tailTimeUs, model.getMode().key);
					timelines.put(tailTimeMs, timeline);
				}
				tailTl.setBPM(model.getBpm());
				tailTl.setNote(mapping[columnIdx], tail);
				timelines.put(Integer.parseInt(hitObject.objectParams.get(0)), tailTl);

				head.setPair(tail);
				tail.setPair(head);
			} else {
				NormalNote note = new NormalNote(wavIdx, hitObject.time * 1000, 0);
				timeline.setNote(mapping[columnIdx], note);
			}
		}
		model.setWavList(wavmap.toArray(new String[wavmap.size()]));
		model.setAllTimeLine(timelines.values().stream().collect(Collectors.toList()).toArray(new TimeLine[timelines.size()]));
		String[] bgaList = {model.getBackbmp()};
		model.setBgaList(bgaList);
		model.setChartInformation(new ChartInformation(f, lntype, null));
		return model;
	}
}
