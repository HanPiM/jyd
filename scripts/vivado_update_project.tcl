proc fail {msg} {
  puts stderr $msg
  exit 1
}

if {$argc != 3} {
  fail "usage: vivado_update_project.tcl <project_path> <irom_coe> <dram_coe>"
}

set project_path [file normalize [lindex $argv 0]]
set irom_coe [file normalize [lindex $argv 1]]
set dram_coe [file normalize [lindex $argv 2]]

foreach required [list $project_path $irom_coe $dram_coe] {
  if {![file exists $required]} {
    fail "required file not found: $required"
  }
}

proc collect_files_recursive {dir} {
  set files {}
  foreach path [glob -nocomplain -directory $dir *] {
    if {[file isdirectory $path]} {
      set files [concat $files [collect_files_recursive $path]]
    } elseif {[file isfile $path]} {
      lappend files [file normalize $path]
    }
  }
  return $files
}

proc remove_missing_files_from_filesets {} {
  foreach fileset_name [get_filesets] {
    set fileset_files [get_files -quiet -of_objects $fileset_name]
    set missing_files {}
    foreach src $fileset_files {
      if {![file exists $src]} {
        lappend missing_files $src
      }
    }
    if {[llength $missing_files] > 0} {
      puts "Removing missing files from $fileset_name:"
      foreach missing_file $missing_files {
        puts "  $missing_file"
      }
      remove_files -quiet -fileset $fileset_name $missing_files
    }
  }
}

proc refresh_pack_fpga_sources {project_path} {
  set project_dir [file dirname $project_path]
  set pack_dir [file normalize [file join $project_dir digital_twin.srcs sources_1 imports pack-fpga]]
  set fileset_obj [get_filesets sources_1]
  if {[llength $fileset_obj] == 0} {
    fail "fileset 'sources_1' not found in project"
  }

  puts "Refreshing pack-fpga sources from $pack_dir"
  set existing_files [get_files -of_objects [lindex $fileset_obj 0]]
  set stale_files {}
  foreach src $existing_files {
    set normalized_src [file normalize $src]
    if {[string first $pack_dir $normalized_src] == 0} {
      lappend stale_files $src
    }
  }
  if {[llength $stale_files] > 0} {
    remove_files -fileset sources_1 $stale_files
  }

  set new_files [collect_files_recursive $pack_dir]
  if {[llength $new_files] == 0} {
    fail "no pack-fpga sources found under $pack_dir"
  }
  add_files -norecurse -fileset sources_1 $new_files
  update_compile_order -fileset sources_1
}

proc set_ip_coe {ip_name coe_path} {
  set ip_obj [get_ips $ip_name]
  if {[llength $ip_obj] == 0} {
    fail "IP '$ip_name' not found"
  }
  puts "Setting $ip_name COE to $coe_path"
  set_property -dict [list CONFIG.Coe_File $coe_path] [lindex $ip_obj 0]
}

puts "Opening project: $project_path"
if {[catch {open_project $project_path} open_err]} {
  fail "open_project failed: $open_err"
}

remove_missing_files_from_filesets
refresh_pack_fpga_sources $project_path
set_ip_coe blk_mem_gen_irom $irom_coe
set_ip_coe blk_mem_gen_dram $dram_coe

puts "Project update complete"
close_project
exit 0
