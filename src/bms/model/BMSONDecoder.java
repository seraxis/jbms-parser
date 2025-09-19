package bms.model;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static bms.model.DecodeLog.State.*;

import bms.model.Layer.EventType;
import bms.model.bmson.*;
import bms.model.bmson.Note;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * bmsonデコーダー
 * 
 * @author exch
 */
public class BMSONDecoder extends ChartDecoder {

	private final ObjectMapper mapper = new ObjectMapper();

	private BMSModel model;

	private final TreeMap<Integer, TimeLineCache> tlcache = new TreeMap<Integer, TimeLineCache>();

	public BMSONDecoder(int lntype) {
		this.lntype = lntype;
	}

	public BMSModel decode(ChartInformation info) {
		this.lntype = info.lntype;
		return decode(info.path);
	}

	public BMSModel decode(Path f) {
		Logger.getGlobal().fine("BMSONファイル解析開始 :" + f.toString());
		log.clear();
		tlcache.clear();
		final long currnttime = System.currentTimeMillis();
		// BMS読み込み、ハッシュ値取得
		model = new BMSModel();
		Bmson bmson = null;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			bmson = mapper.readValue(new DigestInputStream(new BufferedInputStream(Files.newInputStream(f)), digest),
					Bmson.class);
			model.setSHA256(BMSDecoder.convertHexString(digest.digest()));
		} catch (NoSuchAlgorithmException | IOException e) {
			e.printStackTrace();
			return null;
		}
		
		model.setTitle(bmson.info.title);
		model.setSubTitle((bmson.info.subtitle != null ? bmson.info.subtitle : "")
				+ (bmson.info.subtitle != null && bmson.info.subtitle.length() > 0 && bmson.info.chart_name != null
						&& bmson.info.chart_name.length() > 0 ? " " : "")
				+ (bmson.info.chart_name != null && bmson.info.chart_name.length() > 0
						? "[" + bmson.info.chart_name + "]" : ""));
		model.setArtist(bmson.info.artist);
		StringBuilder subartist = new StringBuilder();
		for (String s : bmson.info.subartists) {
			subartist.append((subartist.length() > 0 ? "," : "") + s);
		}
		model.setSubArtist(subartist.toString());
		model.setGenre(bmson.info.genre);

		if (bmson.info.judge_rank < 0) {
			log.add(new DecodeLog(WARNING, "judge_rankが0以下です。judge_rank = " + bmson.info.judge_rank));
		} else if (bmson.info.judge_rank < 5) {
			model.setJudgerank(bmson.info.judge_rank);
			log.add(new DecodeLog(WARNING, "judge_rankの定義が仕様通りでない可能性があります。judge_rank = " + bmson.info.judge_rank));
			model.setJudgerankType(BMSModel.JudgeRankType.BMS_RANK);
		} else {
			model.setJudgerank(bmson.info.judge_rank);
			model.setJudgerankType(BMSModel.JudgeRankType.BMSON_JUDGERANK);
		}

		if(bmson.info.total > 0) {
			model.setTotal(bmson.info.total);
			model.setTotalType(BMSModel.TotalType.BMSON);
		} else {
			log.add(new DecodeLog(WARNING, "totalが0以下です。total = " + bmson.info.total));
		}

		model.setBpm(bmson.info.init_bpm);
		model.setPlaylevel(String.valueOf(bmson.info.level));
		final Mode mode = Mode.getMode(bmson.info.mode_hint);
		if(mode != null) {
			model.setMode(mode);			
		} else {
			log.add(new DecodeLog(WARNING, "非対応のmode_hintです。mode_hint = " + bmson.info.mode_hint));
			model.setMode(Mode.BEAT_7K);
		}
		if (bmson.info.ln_type > 0 && bmson.info.ln_type <= 3) {
			model.setLnmode(bmson.info.ln_type);
		}
		final int[] keyassign;
		switch (model.getMode()) {
		case BEAT_5K:
			keyassign = new int[] { 0, 1, 2, 3, 4, -1, -1, 5 };
			break;
		case BEAT_10K:
			keyassign = new int[] { 0, 1, 2, 3, 4, -1, -1, 5, 6, 7, 8, 9, 10, -1, -1, 11 };
			break;
		default:
			keyassign = new int[model.getMode().key];
			for (int i = 0; i < keyassign.length; i++) {
				keyassign[i] = i;
			}
		}
		List<LongNote>[] lnlist = new List[model.getMode().key];
		Map<Note, LongNote> lnup = new HashMap<Note, LongNote>();

		model.setBanner(bmson.info.banner_image);
		model.setBackbmp(bmson.info.back_image);
		model.setStagefile(bmson.info.eyecatch_image);
		model.setPreview(bmson.info.preview_music);
		final TimeLine basetl = new TimeLine(0, 0, model.getMode().key);
		basetl.setBPM(model.getBpm());
		tlcache.put(0, new TimeLineCache(0.0, basetl));

		if (bmson.bpm_events == null) {
			bmson.bpm_events = new BpmEvent[0];
		}
		if (bmson.stop_events == null) {
			bmson.stop_events = new StopEvent[0];
		}
		if (bmson.scroll_events == null) {
			bmson.scroll_events = new ScrollEvent[0];
		}

		final double resolution = bmson.info.resolution > 0 ? bmson.info.resolution * 4 : 960;
		final Comparator<BMSONObject> comparator = (n1,n2) -> (n1.y - n2.y);

		int bpmpos = 0;
		int stoppos = 0;
		int scrollpos = 0;
		// bpmNotes, stopNotes処理
		Arrays.sort(bmson.bpm_events, comparator);
		Arrays.sort(bmson.stop_events, comparator);
		Arrays.sort(bmson.scroll_events, comparator);

		while (bpmpos < bmson.bpm_events.length || stoppos < bmson.stop_events.length || scrollpos < bmson.scroll_events.length) {
			final int bpmy = bpmpos < bmson.bpm_events.length ? bmson.bpm_events[bpmpos].y : Integer.MAX_VALUE;
			final int stopy = stoppos < bmson.stop_events.length ? bmson.stop_events[stoppos].y : Integer.MAX_VALUE;
			final int scrolly = scrollpos < bmson.scroll_events.length ? bmson.scroll_events[scrollpos].y : Integer.MAX_VALUE;
			if (scrolly <= stopy && scrolly <= bpmy) {
				getTimeLine(scrolly, resolution).setScroll(bmson.scroll_events[scrollpos].rate);
				scrollpos++;
			} else if (bpmy <= stopy) {
				if(bmson.bpm_events[bpmpos].bpm > 0) {
					getTimeLine(bpmy, resolution).setBPM(bmson.bpm_events[bpmpos].bpm);					
				} else {
					log.add(new DecodeLog(WARNING,
							"negative BPMはサポートされていません - y : " + bmson.bpm_events[bpmpos].y + " bpm : " + bmson.bpm_events[bpmpos].bpm));
				}
				bpmpos++;
			} else if (stopy != Integer.MAX_VALUE) {
				if(bmson.stop_events[stoppos].duration >= 0) {
					final TimeLine tl = getTimeLine(stopy, resolution);
					tl.setStop((long) ((1000.0 * 1000 * 60 * 4 * bmson.stop_events[stoppos].duration)
							/ (tl.getBPM() * resolution)));					
				} else {
					log.add(new DecodeLog(WARNING,
							"negative STOPはサポートされていません - y : " + bmson.stop_events[stoppos].y + " bpm : " + bmson.stop_events[stoppos].duration));					
				}
				stoppos++;
			}
		}
		// lines処理(小節線)
		if (bmson.lines != null) {
			for (BarLine bl : bmson.lines) {
				getTimeLine(bl.y, resolution).setSectionLine(true);
			}
		}

		String[] wavmap = new String[bmson.sound_channels.length + bmson.key_channels.length + bmson.mine_channels.length];
		int id = 0;
		long starttime = 0;
		for (SoundChannel sc : bmson.sound_channels) {
			wavmap[id] = sc.name;
			Arrays.sort(sc.notes, comparator);
			final int length = sc.notes.length;
			for (int i = 0; i < length; i++) {
				final bms.model.bmson.Note n = sc.notes[i];
				bms.model.bmson.Note next = null;
				for (int j = i + 1; j < length; j++) {
					if (sc.notes[j].y > n.y) {
						next = sc.notes[j];
						break;
					}
				}
				long duration = 0;
				if (!n.c) {
					starttime = 0;
				}
				TimeLine tl = getTimeLine(n.y, resolution);
				if (next != null && next.c) {
					duration = getTimeLine(next.y, resolution).getMicroTime() - tl.getMicroTime();
				}

				final int key = n.x > 0 && n.x <= keyassign.length ? keyassign[n.x - 1] : -1;
				if (key < 0) {
					// BGノート
					tl.addBackGroundNote(new NormalNote(id, starttime, duration));
				} else if (n.up) {
					// LN終端音定義
					boolean assigned = false;
					if (lnlist[key] != null) {
						final double section = (n.y / resolution);
						for (LongNote ln : lnlist[key]) {
							if (section == ln.getPair().getSection()) {
								ln.getPair().setWav(id);
								ln.getPair().setMicroStarttime(starttime);
								ln.getPair().setMicroDuration(duration);
								assigned = true;
								break;
							}
						}
						if(!assigned) {
							lnup.put(n, new LongNote(id, starttime, duration));
						}
					}
				} else {
					boolean insideln = false;
					if (lnlist[key] != null) {
						final double section = (n.y / resolution);
						for (LongNote ln : lnlist[key]) {
							if (ln.getSection() < section && section <= ln.getPair().getSection()) {
								insideln = true;
								break;
							}
						}
					}

					if (insideln) {
						log.add(new DecodeLog(WARNING,
								"LN内にノートを定義しています - x :  " + n.x + " y : " + n.y));
						tl.addBackGroundNote(new NormalNote(id, starttime, duration));
					} else {
						if (n.l > 0) {
							// ロングノート
							TimeLine end = getTimeLine(n.y + n.l, resolution);
							LongNote ln = new LongNote(id, starttime, duration);
							if (tl.getNote(key) != null) {
								// レイヤーノート判定
								bms.model.Note en = tl.getNote(key);
								if (en instanceof LongNote && end.getNote(key) == ((LongNote) en).getPair()) {
									en.addLayeredNote(ln);
								} else {
									log.add(new DecodeLog(WARNING,
											"同一の位置にノートが複数定義されています - x :  " + n.x + " y : " + n.y));
								}
							} else {
								boolean existNote = false;
								for (TimeLineCache tl2 : tlcache.subMap(n.y, false, n.y + n.l, true).values()) {
									if (tl2.timeline.existNote(key)) {
										existNote = true;
										break;
									}
								}
								if (existNote) {
									log.add(new DecodeLog(WARNING,
											"LN内にノートを定義しています - x :  " + n.x + " y : " + n.y));
									tl.addBackGroundNote(new NormalNote(id, starttime, duration));
								} else {
									tl.setNote(key, ln);
									// ln.setDuration(end.getTime() -
									// start.getTime());
									LongNote lnend = null;
									for (Entry<bms.model.bmson.Note, LongNote> up : lnup.entrySet()) {
										if (up.getKey().y == n.y + n.l && up.getKey().x == n.x) {
											lnend = up.getValue();
											break;
										}
									}
									if(lnend == null) {
										lnend = new LongNote(-2);
									}

									end.setNote(key, lnend);
									ln.setType(n.t > 0 && n.t <= 3 ? n.t : model.getLnmode());
									ln.setPair(lnend);
									if (lnlist[key] == null) {
										lnlist[key] = new ArrayList<LongNote>();
									}
									lnlist[key].add(ln);
								}
							}
						} else {
							// 通常ノート
							if (tl.existNote(key)) {
								if (tl.getNote(key) instanceof NormalNote) {
									tl.getNote(key).addLayeredNote(new NormalNote(id, starttime, duration));
								} else {
									log.add(new DecodeLog(WARNING,
											"同一の位置にノートが複数定義されています - x :  " + n.x + " y : " + n.y));
								}
							} else {
								tl.setNote(key, new NormalNote(id, starttime, duration));
							}
						}
					}
				}
				starttime += duration;
			}
			id++;
		}
		
		for (MineChannel sc : bmson.key_channels) {
			wavmap[id] = sc.name;
			Arrays.sort(sc.notes, comparator);
			final int length = sc.notes.length;
			for (int i = 0; i < length; i++) {
				final bms.model.bmson.MineNote n = sc.notes[i];
				TimeLine tl = getTimeLine(n.y, resolution);

				final int key = n.x > 0 && n.x <= keyassign.length ? keyassign[n.x - 1] : -1;
				if (key >= 0) {
					// BGノート
					tl.setHiddenNote(key, new NormalNote(id));
				}
			}
			id++;
		}
		for (MineChannel sc : bmson.mine_channels) {
			wavmap[id] = sc.name;
			Arrays.sort(sc.notes, comparator);
			final int length = sc.notes.length;
			for (int i = 0; i < length; i++) {
				final bms.model.bmson.MineNote n = sc.notes[i];
				TimeLine tl = getTimeLine(n.y, resolution);

				final int key = n.x > 0 && n.x <= keyassign.length ? keyassign[n.x - 1] : -1;
				if (key >= 0) {
					boolean insideln = false;
					if (lnlist[key] != null) {
						final double section = (n.y / resolution);
						for (LongNote ln : lnlist[key]) {
							if (ln.getSection() < section && section <= ln.getPair().getSection()) {
								insideln = true;
								break;
							}
						}
					}

					if (insideln) {
						log.add(new DecodeLog(WARNING,
								"LN内に地雷ノートを定義しています - x :  " + n.x + " y : " + n.y));
					} else if(tl.existNote(key)){
						log.add(new DecodeLog(WARNING,
								"地雷ノートを定義している位置に通常ノートが存在します - x :  " + n.x + " y : " + n.y));
					} else {
						tl.setNote(key, new MineNote(id, n.damage));						
					}
				}
			}
			id++;
		}

		model.setWavList(wavmap);
		// BGA処理
		if (bmson.bga != null && bmson.bga.bga_header != null) {
			final String[] bgamap = new String[bmson.bga.bga_header.length];
			final Map<Integer, Integer> idmap = new HashMap<Integer, Integer>(bmson.bga.bga_header.length);
			final Map<Integer, Layer.Sequence[]> seqmap = new HashMap<Integer, Layer.Sequence[]>();
			for (int i = 0; i < bmson.bga.bga_header.length; i++) {
				BGAHeader bh = bmson.bga.bga_header[i];
				bgamap[i] = bh.name;
				idmap.put(bh.id, i);
			}
			if (bmson.bga.bga_sequence != null) {
				for (BGASequence n : bmson.bga.bga_sequence) {
					if(n != null) {
						Layer.Sequence[] sequence = new Layer.Sequence[n.sequence.length];
						for(int i =0;i < sequence.length;i++) {
							Sequence seq = n.sequence[i];
							if(seq.id != Integer.MIN_VALUE) {
								sequence[i] = new Layer.Sequence(seq.time, seq.id);
							} else {
								sequence[i] = new Layer.Sequence(seq.time);								
							}
						}
						seqmap.put(n.id, sequence);
					}
				}
			}
			if (bmson.bga.bga_events != null) {
				for (BNote n : bmson.bga.bga_events) {
					getTimeLine(n.y, resolution).setBGA(idmap.get(n.id));
				}
			}
			if (bmson.bga.layer_events != null) {
				for (BNote n : bmson.bga.layer_events) {
					int[] idset = n.id_set != null ? n.id_set : new int[] {n.id};
					Layer.Sequence[][] seqs = new Layer.Sequence[idset.length][];
					Layer.Event event = null;
					switch(n.condition != null ? n.condition : "") {
					case "play":
						event = new Layer.Event(EventType.PLAY, n.interval);
						break;
					case "miss":
						event = new Layer.Event(EventType.MISS, n.interval);
						break;
					default:								
						event = new Layer.Event(EventType.ALWAYS, n.interval);
					}
					for(int seqindex = 0; seqindex < seqs.length;seqindex++) {
						int nid = idset[seqindex];
						if(seqmap.containsKey(nid) ) {
							seqs[seqindex] = seqmap.get(nid);
						} else {
							seqs[seqindex] = new Layer.Sequence[] {new Layer.Sequence(0, idmap.get(n.id)),new Layer.Sequence(500)};
						}						
					}
					getTimeLine(n.y, resolution).setEventlayer(new Layer[] {new Layer(event, seqs)});						
				}
			}
			if (bmson.bga.poor_events != null) {
				for (BNote n : bmson.bga.poor_events) {
					if(seqmap.containsKey(n.id) ) {
						getTimeLine(n.y, resolution).setEventlayer(new Layer[] {new Layer(new Layer.Event(EventType.MISS, 1),
								new Layer.Sequence[][] {seqmap.get(n.id)})});						
					} else {
						getTimeLine(n.y, resolution).setEventlayer(new Layer[] {new Layer(new Layer.Event(EventType.MISS, 1),
								new Layer.Sequence[][] {{new Layer.Sequence(0, idmap.get(n.id)),new Layer.Sequence(500)}})});
					}
				}
			}
			model.setBgaList(bgamap);
		}
		model.setAllTimeLine(tlcache.values().stream().map(tlc -> tlc.timeline).collect(Collectors.toList()).toArray(new TimeLine[tlcache.size()]));

		Logger.getGlobal().fine("BMSONファイル解析完了 :" + f.toString() + " - TimeLine数:" + tlcache.size() + " 時間(ms):"
				+ (System.currentTimeMillis() - currnttime));
		
		model.setChartInformation(new ChartInformation(f, lntype, null));
		printLog(f);
		return model;
	}
	
	private TimeLine getTimeLine(int y, double resolution) {
		// Timeをus単位にする場合はこのメソッド内部だけ変更すればOK
		final TimeLineCache tlc = tlcache.get(y);
		if (tlc != null) {
			return tlc.timeline;
		}

		Entry<Integer, TimeLineCache> le = tlcache.lowerEntry(y);
		double bpm = le.getValue().timeline.getBPM();
		double time = le.getValue().time + le.getValue().timeline.getMicroStop()
				+ (240000.0 * 1000 * ((y - le.getKey()) / resolution)) / bpm;

		TimeLine tl = new TimeLine(y / resolution, (long) time, model.getMode().key);
		tl.setBPM(bpm);
		tlcache.put(y, new TimeLineCache(time, tl));
		// System.out.println("y = " + y + " , bpm = " + bpm + " , time = " +
		// tl.getTime());
		return tl;
	}	
}
